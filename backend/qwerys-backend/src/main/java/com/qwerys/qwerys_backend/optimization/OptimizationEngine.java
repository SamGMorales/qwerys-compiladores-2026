package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Central entry point for SQL optimization analysis.
 *
 * <p>Holds an ordered registry of all {@link OptimizationRule} implementations and
 * applies every rule that {@link OptimizationRule#applies} to the given AST + query.
 * Suggestions are returned sorted by impact: HIGH → MEDIUM → LOW.
 *
 * <p>Usage:
 * <pre>{@code
 * OptimizationEngine engine = new OptimizationEngine();
 * List<OptimizationSuggestion> suggestions = engine.optimize(ast, rawQuery);
 * }</pre>
 */
@Component
public class OptimizationEngine {

    private final List<OptimizationRule> rules;

    public OptimizationEngine() {
        rules = List.of(
                new SelectStarRule(),       // OPT-001 HIGH
                new MissingIndexRule(),     // OPT-002 HIGH
                new NoPaginationRule(),     // OPT-003 MEDIUM
                new ImplicitJoinRule(),     // OPT-004 MEDIUM
                new SubqueryInWhereRule(),  // OPT-005 HIGH
                new NullComparisonRule(),   // OPT-006 HIGH
                new FunctionOnIndexRule(),  // OPT-007 HIGH
                new CartesianProductRule(), // OPT-008 HIGH
                new NotInSubqueryRule(),    // OPT-009 MEDIUM
                new RedundantOrderByRule(),  // OPT-010 LOW
                new LeadingWildcardRule(),    // OPT-011 HIGH
                new CursorLoopRule(),          // OPT-CURSOR-LOOP MEDIUM (procedural)
                new CursorReplaceableByUpdateRule(), // OPT-PROC-001 HIGH
                new RowByRowProcessingRule(),       // OPT-PROC-002 HIGH
                new RepeatedQueryInLoopRule(),        // OPT-PROC-003 MEDIUM
                new UnnecessaryDynamicSqlRule(),      // OPT-PROC-004 MEDIUM
                new CommitInLoopRule(),               // OPT-PROC-005 HIGH
                new InefficientStringConcatRule()    // OPT-PROC-006 MEDIUM
        );
    }

    /**
     * Evaluates all registered rules against the provided AST and raw query string.
     *
     * <p>Rules that throw unexpected exceptions are silently skipped so that a single
     * broken rule never prevents other suggestions from being returned.
     *
     * @param ast   root node produced by the SQL parser (must not be {@code null})
     * @param query original SQL string (must not be {@code null})
     * @return unmodifiable list of suggestions sorted HIGH → MEDIUM → LOW
     */
    public List<OptimizationSuggestion> optimize(AstNode ast, String query) {
        return optimize(ast, query, SqlDialect.GENERIC);
    }

    /**
     * Dialect-aware variant of {@link #optimize(AstNode, String)}.
     * Applies the same rules but adjusts dialect-specific suggestion fragments
     * (e.g. OPT-003 uses {@code FETCH FIRST} for Oracle and {@code LIMIT} for all others).
     *
     * @param ast     root node produced by the SQL parser (must not be {@code null})
     * @param query   original SQL string (must not be {@code null})
     * @param dialect target SQL dialect — controls syntax in generated fragments
     * @return unmodifiable list of suggestions sorted HIGH → MEDIUM → LOW (after deduplication)
     */
    public List<OptimizationSuggestion> optimize(AstNode ast, String query, SqlDialect dialect) {
        return optimize(ast, query, dialect, null);
    }

    /**
     * Dialect-aware optimization with optional live-schema column names for the primary table.
     * When {@code schemaColumns} is null or empty, behavior matches {@link #optimize(AstNode, String, SqlDialect)}.
     */
    public List<OptimizationSuggestion> optimize(
            AstNode ast, String query, SqlDialect dialect, List<String> schemaColumns) {
        return optimizeWithMetrics(ast, query, dialect, schemaColumns).suggestions();
    }

    /**
     * Same as {@link #optimize(AstNode, String, SqlDialect, List)} but also returns how many
     * registered rules were evaluated (for expert-mode metrics).
     */
    public OptimizationResult optimizeWithMetrics(
            AstNode ast, String query, SqlDialect dialect, List<String> schemaColumns) {
        List<String> cols = schemaColumns == null || schemaColumns.isEmpty() ? null : List.copyOf(schemaColumns);
        List<OptimizationSuggestion> suggestions = rules.stream()
                .filter(rule -> safeApplies(rule, ast, query))
                .map(rule -> safeApply(rule, ast, query, cols))
                .filter(s -> s != null)
                .map(s -> applyDialectAdjustment(s, query, dialect, cols))
                .sorted(Comparator.comparingInt(s -> impactOrder(s.impact())))
                .collect(Collectors.toCollection(ArrayList::new));
        suppressRedundantCursorLoop(suggestions);
        return new OptimizationResult(List.copyOf(suggestions), rules.size());
    }

    /**
     * OPT-PROC-001 is a higher-impact, catalogued variant of the same cursor + row-DML pattern as
     * OPT-CURSOR-LOOP. Removes OPT-CURSOR-LOOP entries only when some OPT-PROC-001 suggestion
     * correlates (same fragment span or same procedural cursor+DML topic), so unrelated findings stay visible.
     */
    private static void suppressRedundantCursorLoop(List<OptimizationSuggestion> suggestions) {
        List<OptimizationSuggestion> proc001 = suggestions.stream()
                .filter(s -> "OPT-PROC-001".equals(s.ruleId()))
                .toList();
        if (proc001.isEmpty()) {
            return;
        }
        suggestions.removeIf(s -> {
            if (!"OPT-CURSOR-LOOP".equals(s.ruleId())) {
                return false;
            }
            return proc001.stream().anyMatch(p -> cursorLoopRedundantWithProc001(p, s));
        });
    }

    private static boolean cursorLoopRedundantWithProc001(OptimizationSuggestion proc001, OptimizationSuggestion cursorLoop) {
        if (fragmentsAlign(proc001, cursorLoop)) {
            return true;
        }
        return bothDescribeProceduralCursorRowDml(proc001, cursorLoop);
    }

    /** Strong signal when rules emit overlapping SQL excerpts (or identical placeholders). */
    private static boolean fragmentsAlign(OptimizationSuggestion a, OptimizationSuggestion b) {
        String na = normalizeSuggestionFragments(a);
        String nb = normalizeSuggestionFragments(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    private static String normalizeSuggestionFragments(OptimizationSuggestion s) {
        String o = s.originalFragment() == null ? "" : s.originalFragment();
        String opt = s.optimizedFragment() == null ? "" : s.optimizedFragment();
        return (o + " " + opt).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Fallback when templates differ but still describe cursor iteration plus row-level UPDATE/DELETE. */
    private static boolean bothDescribeProceduralCursorRowDml(OptimizationSuggestion proc001, OptimizationSuggestion cursorLoop) {
        String p = normalizeSuggestionFragments(proc001);
        String c = normalizeSuggestionFragments(cursorLoop);
        return mentionsCursorIteration(p) && mentionsCursorIteration(c)
                && mentionsRowDml(p) && mentionsRowDml(c);
    }

    private static boolean mentionsCursorIteration(String normalized) {
        return normalized.contains("fetch") || normalized.contains("cursor") || normalized.contains("loop");
    }

    private static boolean mentionsRowDml(String normalized) {
        return normalized.contains("update") || normalized.contains("delete");
    }

    /**
     * Adjusts the {@code optimizedFragment} of dialect-sensitive rules.
     * Currently handles:
     * <ul>
     *   <li>OPT-003 (NoPaginationRule) — Oracle uses {@code FETCH FIRST n ROWS ONLY};
     *       all other dialects use {@code LIMIT n}.</li>
     * </ul>
     */
    private OptimizationSuggestion applyDialectAdjustment(
            OptimizationSuggestion s, String query, SqlDialect dialect, List<String> schemaColumns) {
        if (!"OPT-003".equals(s.ruleId())) {
            return s;
        }

        String trimmed = query.stripTrailing();
        boolean hasSemi = trimmed.endsWith(";");
        String base = hasSemi ? trimmed.substring(0, trimmed.length() - 1).stripTrailing() : trimmed;
        if (schemaColumns != null && !schemaColumns.isEmpty()) {
            base = SchemaOptimizationSupport.expandSelectStar(base, schemaColumns);
        }

        String optimized = switch (dialect) {
            case ORACLE    -> base + " FETCH FIRST 100 ROWS ONLY" + (hasSemi ? ";" : "");
            case SQLSERVER -> injectSqlServerTop(base, 100) + (hasSemi ? ";" : "");
            default        -> base + " LIMIT 100" + (hasSemi ? ";" : "");
        };

        return new OptimizationSuggestion(s.ruleId(), s.description(), s.originalFragment(), optimized, s.impact());
    }

    private static final Pattern SQL_SERVER_SELECT_HEAD =
            Pattern.compile("(?is)^(\\s*SELECT\\s+(?:DISTINCT\\s+|ALL\\s+)?)(.+)$");

    /** Inserts {@code TOP n} immediately after {@code SELECT} for SQL Server pagination hints. */
    private static String injectSqlServerTop(String base, int topN) {
        Matcher m = SQL_SERVER_SELECT_HEAD.matcher(base);
        if (m.matches()) {
            return m.group(1) + "TOP " + topN + " " + m.group(2).stripLeading();
        }
        return base;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean safeApplies(OptimizationRule rule, AstNode ast, String query) {
        try {
            return rule.applies(ast, query);
        } catch (Exception ignored) {
            return false;
        }
    }

    private OptimizationSuggestion safeApply(
            OptimizationRule rule, AstNode ast, String query, List<String> schemaColumns) {
        try {
            return rule.apply(ast, query, schemaColumns);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int impactOrder(String impact) {
        return switch (impact.toUpperCase()) {
            case "HIGH"   -> 0;
            case "MEDIUM" -> 1;
            case "LOW"    -> 2;
            default       -> 3;
        };
    }
}
