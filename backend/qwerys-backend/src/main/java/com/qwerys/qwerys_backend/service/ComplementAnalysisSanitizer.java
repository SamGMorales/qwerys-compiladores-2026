package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.ai.AiLocaleHelper;
import com.qwerys.qwerys_backend.model.ai.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Filters noisy AI output and fills missing warning messages.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Removes empty/no-op "no errors found" entries.</li>
 *   <li>Deduplicates AI optimizations that overlap native ones (OPT-001/002/003/005…)
 *       by comparing normalized originalFragment / optimizedFragment / ruleId.</li>
 *   <li>Drops AI optimizations whose optimizedFragment is just an {@code EXPLAIN} command
 *       (diagnostic, not an optimization).</li>
 *   <li>Drops folklore advice that is technically wrong for the engine
 *       (e.g. "LIMIT without OFFSET is non-deterministic" — wrong, the issue is missing ORDER BY;
 *        "create an index on a boolean column").</li>
 *   <li>Drops dialect-mismatched fragments (existing logic).</li>
 * </ul>
 */
final class ComplementAnalysisSanitizer {

    /**
     * Matches "optimizations" that are actually pure diagnostics returned by the engine,
     * across SQL and NoSQL:
     * <ul>
     *   <li>SQL: {@code EXPLAIN <query>}, {@code EXPLAIN ANALYZE ...}, {@code SET SHOWPLAN_ALL ON}</li>
     *   <li>MongoDB: {@code db.x.find(...).explain("executionStats")}, {@code db.x.stats()}</li>
     *   <li>Elasticsearch: {@code GET /idx/_search?explain=true}, {@code "explain": true}, {@code GET /_nodes/stats}</li>
     *   <li>Cassandra: {@code TRACING ON; ... TRACING OFF;}</li>
     *   <li>DynamoDB: {@code aws dynamodb describe-table}, {@code DescribeContributorInsights}</li>
     * </ul>
     * A diagnostic does not improve the query, so it is never a legitimate optimization.
     */
    private static final Pattern DIAGNOSTIC_ONLY = Pattern.compile(
            "(?is)^\\s*("
                    + "EXPLAIN\\b.*"
                    + "|SET\\s+SHOWPLAN(?:_ALL|_TEXT|_XML)?\\s+ON\\b.*"
                    + "|SET\\s+STATISTICS\\s+(?:IO|TIME|PROFILE)\\s+ON\\b.*"
                    + "|TRACING\\s+ON\\b.*"
                    // MongoDB: matches db.<coll>.explain(...) AND chained forms like
                    // db.<coll>.find({...}).explain(...) or db.<coll>.aggregate([...]).explain(...)
                    + "|db\\.[A-Za-z0-9_]+.*?\\.(?:explain|stats|getIndexStats|getPlanCache)\\s*\\(.*"
                    + "|GET\\s+/[^\\s?]+\\?explain=true.*"
                    + "|GET\\s+/_nodes/stats.*"
                    + "|GET\\s+/_cluster/(?:health|stats).*"
                    + "|aws\\s+dynamodb\\s+(?:describe-table|describe-contributor-insights).*"
                    + ")$");

    /**
     * Phrases the LLM tends to repeat that are technically wrong or folklore,
     * spanning SQL and NoSQL engines. Matching is case-insensitive substring on
     * {@code description}/{@code originalFragment}/{@code optimizedFragment}.
     */
    private static final String[] FOLKLORE_FRAGMENTS = {
            "limit without offset",
            "limit sin offset",
            "limit con offset",
            "índice en columna booleana",
            "index on boolean",
            "index on a boolean",
            "indice en columna booleana",
            "use allow filtering",
            "usar allow filtering",
            "con allow filtering",
            "denormalize everything",
            "desnormaliza todo",
            "desnormalizar todo",
            "always shard your",
            "siempre fragmenta tu",
            "use keys *",
            "use keys command",
            "usar keys *",
            "usar comando keys",
            "$where for",
            "$where para",
            "usar $where",
            "use $where",
            "disable refresh_interval",
            "desactiva refresh_interval",
            "desactivar refresh_interval",
            "store json as string",
            "almacena json como string",
            "almacenar json como string",
            "use flushall",
            "usar flushall",
            "tracing on",
            "tracing off"
    };

    private ComplementAnalysisSanitizer() {
    }

    static ComplementAnalysisResponse sanitize(ComplementAnalysisResponse res, ComplementAnalysisRequest req) {
        if (res == null || !res.success()) {
            return res;
        }
        String db = req.databaseType() != null ? req.databaseType().toLowerCase(Locale.ROOT) : "mysql";
        Locale locale = AiLocaleHelper.resolve(req.locale());

        List<AnalysisErrorDto> errors = filterErrors(res.additionalErrors());
        List<AnalysisWarningDto> warnings = filterWarnings(res.additionalWarnings(), locale);
        List<OptimizationDto> opts = filterOptimizations(res.additionalOptimizations(), db, req.optimizations());
        List<NativeFindingReviewDto> reviews = CustomEngineAnalysisSupport.adjustWrongEngineReviewsForCustomSqlLike(
                res.nativeReviews(), req, locale);

        return ComplementAnalysisResponse.ok(
                res.pedagogy(),
                res.optimizationNotes(),
                res.validityCorrection(),
                reviews,
                errors,
                warnings,
                opts,
                res.syntaxCorrections() != null ? res.syntaxCorrections() : List.of(),
                res.secondPassOverlay(),
                res.aiAvailable(),
                res.provider(),
                res.responseTimeMs() != null ? res.responseTimeMs() : 0L);
    }

    private static List<AnalysisErrorDto> filterErrors(List<AnalysisErrorDto> errors) {
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        List<AnalysisErrorDto> out = new ArrayList<>();
        for (AnalysisErrorDto e : errors) {
            if (e == null || isNoiseError(e)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static boolean isNoiseError(AnalysisErrorDto e) {
        String m = (e.message() != null ? e.message() : "").toLowerCase(Locale.ROOT);
        return m.contains("no se han encontrado errores")
                || m.contains("no additional errors")
                || m.contains("no hay errores adicionales")
                || (m.contains("revisar la sintaxis") && m.contains("documentación"));
    }

    private static List<AnalysisWarningDto> filterWarnings(List<AnalysisWarningDto> warnings, Locale locale) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        List<AnalysisWarningDto> out = new ArrayList<>();
        for (AnalysisWarningDto w : warnings) {
            if (w == null) {
                continue;
            }
            String msg = w.message();
            if (msg == null || msg.isBlank()) {
                msg = defaultWarningMessage(w.code(), locale);
            }
            if (msg == null || msg.isBlank()) {
                continue;
            }
            if (isFolklore(msg)) {
                continue;
            }
            out.add(new AnalysisWarningDto(w.code(), w.severity(), msg));
        }
        return out;
    }

    private static String defaultWarningMessage(String code, Locale locale) {
        boolean es = AiLocaleHelper.isSpanish(locale);
        if (code == null) {
            return es ? "Advertencia detectada por la IA." : "Warning detected by AI.";
        }
        return switch (code) {
            case "AI-WARN-INCOMPLETE" -> es
                    ? "La consulta parece un fragmento incompleto; incluye WITH/SELECT al inicio."
                    : "The query may be an incomplete fragment; include WITH/SELECT at the top.";
            case "AI-WARN-SCHEMA" -> es
                    ? "Revisa la conexión o el esquema de la base de datos."
                    : "Review database connection or schema.";
            case "AI-WARN-DDB-MULTI" -> es
                    ? "Varias operaciones en una sola expresión DynamoDB pueden ser difíciles de mantener."
                    : "Multiple operations in one DynamoDB expression can be hard to maintain.";
            default -> es
                    ? "Revisa este punto antes de ejecutar en producción."
                    : "Review this point before running in production.";
        };
    }

    private static List<OptimizationDto> filterOptimizations(
            List<OptimizationDto> opts, String db, List<OptimizationDto> nativeOpts) {
        if (opts == null || opts.isEmpty()) {
            return List.of();
        }
        Set<String> nativeSignatures = nativeSignatures(nativeOpts);
        List<OptimizationDto> out = new ArrayList<>();
        for (OptimizationDto o : opts) {
            if (o == null) {
                continue;
            }
            if (isInvalidDialectOptimization(o, db)) {
                continue;
            }
            if (isDiagnosticOnly(o)) {
                continue;
            }
            if (isFolklore(o.description())
                    || isFolklore(o.originalFragment())
                    || isFolklore(o.optimizedFragment())) {
                continue;
            }
            if (duplicatesNative(o, nativeSignatures, nativeOpts)) {
                continue;
            }
            if (isUnsafeOuterJoinReorder(o)) {
                continue;
            }
            out.add(o);
        }
        return out;
    }

    /** Build signatures for native suggestions so AI duplicates can be detected fast. */
    private static Set<String> nativeSignatures(List<OptimizationDto> nativeOpts) {
        Set<String> sigs = new HashSet<>();
        if (nativeOpts == null) {
            return sigs;
        }
        for (OptimizationDto n : nativeOpts) {
            if (n == null) continue;
            sigs.add(normalize(n.originalFragment()));
            sigs.add(normalize(n.optimizedFragment()));
            sigs.add(normalize(n.description()));
        }
        sigs.remove("");
        return sigs;
    }

    /**
     * AI suggestion duplicates a native one when normalized original/optimized fragments
     * match, when ruleId already exists in native list, or when descriptions are highly similar.
     */
    private static boolean duplicatesNative(
            OptimizationDto ai, Set<String> nativeSignatures, List<OptimizationDto> nativeOpts) {
        if (ai == null) return false;
        String origNorm = normalize(ai.originalFragment());
        String optNorm = normalize(ai.optimizedFragment());
        String descNorm = normalize(ai.description());
        if (nativeSignatures.contains(origNorm)) return true;
        if (nativeSignatures.contains(optNorm)) return true;
        if (nativeOpts != null) {
            for (OptimizationDto n : nativeOpts) {
                if (n == null) continue;
                if (Objects.equals(n.ruleId(), ai.ruleId()) && n.ruleId() != null && !n.ruleId().isBlank()) {
                    return true;
                }
                if (jaccardSimilar(descNorm, normalize(n.description()), 0.7d)) {
                    return true;
                }
                if (jaccardSimilar(optNorm, normalize(n.optimizedFragment()), 0.85d)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isDiagnosticOnly(OptimizationDto o) {
        String opt = o.optimizedFragment() != null ? o.optimizedFragment().strip() : "";
        return DIAGNOSTIC_ONLY.matcher(opt).matches();
    }

    private static boolean isFolklore(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String frag : FOLKLORE_FRAGMENTS) {
            if (lower.contains(frag)) return true;
        }
        return false;
    }

    /**
     * Drops AI optimizations that swap LEFT/RIGHT JOIN table order without changing join type
     * or explaining dialect equivalence (e.g. SQLite RIGHT → LEFT). INNER JOIN rewrites are kept.
     */
    private static boolean isUnsafeOuterJoinReorder(OptimizationDto o) {
        if (o == null) {
            return false;
        }
        String orig = joinContext(o.originalFragment(), o.description());
        String opt = o.optimizedFragment() != null ? o.optimizedFragment() : "";
        if (orig.isBlank() || opt.isBlank()) {
            return false;
        }
        String origU = orig.toUpperCase(Locale.ROOT);
        String optU = opt.toUpperCase(Locale.ROOT);
        if (optU.contains("INNER JOIN")) {
            return false;
        }
        if (isLegitimateRightFullToLeftRewrite(origU, optU)) {
            return false;
        }
        JoinPair origPair = extractOuterJoinPair(orig);
        JoinPair optPair = extractOuterJoinPair(opt);
        if (origPair == null || optPair == null) {
            return false;
        }
        return origPair.isSwappedWith(optPair);
    }

    private static String joinContext(String originalFragment, String description) {
        if (originalFragment != null && !originalFragment.isBlank()) {
            return originalFragment;
        }
        return description != null ? description : "";
    }

    /** RIGHT/FULL rewrites to LEFT with reversed tables are valid dialect transforms (e.g. SQLite LT001). */
    private static boolean isLegitimateRightFullToLeftRewrite(String origU, String optU) {
        boolean origRightOrFull = origU.contains("RIGHT JOIN")
                || origU.contains("FULL OUTER JOIN")
                || origU.contains("FULL JOIN");
        return origRightOrFull && optU.contains("LEFT JOIN") && !origU.contains("LEFT JOIN");
    }

    private static final Pattern OUTER_JOIN_FROM = Pattern.compile(
            "(?is)\\bFROM\\s+(?:[\\w$]+\\.)?(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?\\s+"
                    + "(LEFT|RIGHT)\\s+(?:OUTER\\s+)?JOIN\\s+(?:[\\w$]+\\.)?(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?");

    private static JoinPair extractOuterJoinPair(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        var m = OUTER_JOIN_FROM.matcher(sql);
        if (!m.find()) {
            return null;
        }
        String joinType = m.group(3).toUpperCase(Locale.ROOT);
        String outerTable = m.group(1).toLowerCase(Locale.ROOT);
        String joinedTable = m.group(4).toLowerCase(Locale.ROOT);
        return new JoinPair(joinType, outerTable, joinedTable);
    }

    private record JoinPair(String joinType, String outerTable, String joinedTable) {
        boolean isSwappedWith(JoinPair other) {
            if (other == null || !joinType.equals(other.joinType)) {
                return false;
            }
            return outerTable.equals(other.joinedTable) && joinedTable.equals(other.outerTable);
        }
    }

    private static boolean isInvalidDialectOptimization(OptimizationDto o, String db) {
        // Pad with spaces on both sides so token-boundary checks like " FETCH FIRST " or " TOP "
        // also match tokens that appear at the very start or end of the optimized fragment
        // (e.g. "FETCH FIRST 10 ROWS ONLY" without a leading space).
        String opt = " "
                + (o.optimizedFragment() != null ? o.optimizedFragment() : "").toUpperCase(Locale.ROOT)
                + " ";
        return switch (db) {
            // --- SQL family ---
            case "postgresql" -> opt.contains("WITH (INDEX") || opt.contains("INDEXED BY") || opt.contains(" TOP ");
            case "mysql" -> opt.contains("WITH (INDEX") || opt.contains("INDEXED BY") || opt.contains(" FETCH FIRST ");
            case "oracle" -> opt.contains(" LIMIT ") || opt.contains(" TOP ") || opt.contains("WITH (INDEX");
            case "sqlserver" -> opt.contains("INDEXED BY") || opt.contains(" LIMIT ") || opt.contains(" FETCH FIRST ");
            // SQLite uses standard LIMIT/OFFSET — reject Oracle FETCH FIRST, T-SQL TOP/hints,
            // SQL Server WITH (INDEX = …), PartiQL semantics, etc. Also reject RIGHT/FULL JOIN
            // and PIVOT/UNPIVOT which SQLite does not support.
            case "sqlite" -> opt.contains(" FETCH FIRST ")
                    || opt.contains(" TOP ")
                    || opt.contains("WITH (INDEX")
                    || opt.contains(" RIGHT JOIN ")
                    || opt.contains(" FULL JOIN ")
                    || opt.contains(" FULL OUTER JOIN ")
                    || opt.contains(" PIVOT ")
                    || opt.contains(" UNPIVOT ");

            // --- NoSQL family ---
            // DynamoDB uses condition/projection/update expressions or PartiQL. PartiQL allows
            // SELECT/INSERT/UPDATE/DELETE on a single table but NOT JOIN, GROUP BY, HAVING,
            // ALLOW FILTERING, or Mongo-style chain syntax.
            case "dynamodb" -> opt.contains("COALESCE(")
                    || opt.contains(" JOIN ")
                    || opt.contains(" LEFT JOIN ")
                    || opt.contains(" RIGHT JOIN ")
                    || opt.contains(" GROUP BY ")
                    || opt.contains(" HAVING ")
                    || opt.contains(" ALLOW FILTERING")
                    || opt.contains(" DB.")
                    || opt.contains(".AGGREGATE(");
            // MongoDB: JS / aggregation pipeline. Reject SQL DML/DDL, CQL hints, SQL Server hints.
            case "mongodb" -> opt.contains(" SELECT ")
                    || opt.contains(" INSERT INTO ")
                    || opt.contains(" UPDATE ")
                    || opt.contains(" DELETE FROM ")
                    || opt.contains(" CREATE TABLE ")
                    || opt.contains(" ALLOW FILTERING")
                    || opt.contains("WITH (INDEX")
                    || opt.contains(" FETCH FIRST ");
            // Cassandra (CQL): no JOIN, no GROUP BY across partitions, no HAVING, no subqueries.
            // ALLOW FILTERING is technically valid syntax but is the textbook anti-pattern and
            // should never be a recommended optimization.
            case "cassandra" -> opt.contains(" JOIN ")
                    || opt.contains(" GROUP BY ")
                    || opt.contains(" HAVING ")
                    || opt.contains(" FULL OUTER ")
                    || opt.contains(" RIGHT JOIN ")
                    || opt.contains(" UNION ")
                    || opt.contains(" ALLOW FILTERING")
                    || opt.contains("WITH (INDEX")
                    || opt.contains(" DB.");
            // Redis: command syntax (GET/SET/SCAN/HSET/…). Reject SQL DML/DDL and Mongo pipelines.
            case "redis" -> opt.contains(" SELECT ")
                    || opt.contains(" INSERT INTO ")
                    || opt.contains(" UPDATE ")
                    || opt.contains(" DELETE FROM ")
                    || opt.contains(" JOIN ")
                    || opt.contains(".AGGREGATE(")
                    || opt.contains(".FIND(")
                    || opt.contains(" ALLOW FILTERING");
            // Elasticsearch: Query DSL JSON or _search SQL with strict subset. Reject DML/DDL.
            case "elasticsearch" -> opt.contains(" INSERT INTO ")
                    || opt.contains(" UPDATE ")
                    || opt.contains(" DELETE FROM ")
                    || opt.contains(" CREATE TABLE ")
                    || opt.contains(" JOIN ")
                    || opt.contains(" GROUP BY ")
                    || opt.contains(" ALLOW FILTERING")
                    || opt.contains("WITH (INDEX");
            default -> false;
        };
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s]+", " ")
                .replaceAll("[`\"\\[\\]]", "")
                .strip();
    }

    /** Token-set Jaccard similarity — robust enough to catch paraphrased duplicates. */
    private static boolean jaccardSimilar(String a, String b, double threshold) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        Set<String> ta = tokenize(a);
        Set<String> tb = tokenize(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return false;
        }
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        double j = (double) inter.size() / (double) union.size();
        return j >= threshold;
    }

    private static Set<String> tokenize(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.split("[^a-zA-Z0-9_*]+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }
}
