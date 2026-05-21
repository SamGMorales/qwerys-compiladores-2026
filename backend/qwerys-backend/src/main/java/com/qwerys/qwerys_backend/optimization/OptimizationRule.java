package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.List;

/**
 * Contract for a single SQL optimization rule.
 *
 * <p>Each rule is responsible for:
 * <ol>
 *   <li>Declaring a stable {@link #getRuleId()} (e.g. "OPT-001").</li>
 *   <li>Deciding whether it {@link #applies} to a given AST + raw query.</li>
 *   <li>Producing an {@link OptimizationSuggestion} via {@link #apply} when it does.</li>
 * </ol>
 *
 * <p>Implementations are stateless and thread-safe; they must not modify the AST.
 */
public interface OptimizationRule {

    /** Stable identifier used to reference this rule (e.g. "OPT-001"). */
    String getRuleId();

    /** Short human-readable name describing what this rule checks. */
    String getDescription();

    /**
     * Returns {@code true} if this rule detects a pattern in the given AST or raw query
     * that warrants an optimization suggestion.
     *
     * @param ast   root node of the parsed AST (never {@code null})
     * @param query original SQL string (never {@code null})
     */
    boolean applies(AstNode ast, String query);

    /**
     * Produces an {@link OptimizationSuggestion} for the detected pattern.
     * Must only be called after {@link #applies} returns {@code true}.
     *
     * @param ast   root node of the parsed AST
     * @param query original SQL string
     * @return a fully populated suggestion (never {@code null})
     */
    OptimizationSuggestion apply(AstNode ast, String query);

    /**
     * Schema-aware variant; rules that do not use live columns delegate to {@link #apply(AstNode, String)}.
     *
     * @param schemaColumns column names for the query's primary table (may be null or empty)
     */
    default OptimizationSuggestion apply(AstNode ast, String query, List<String> schemaColumns) {
        return apply(ast, query);
    }
}
