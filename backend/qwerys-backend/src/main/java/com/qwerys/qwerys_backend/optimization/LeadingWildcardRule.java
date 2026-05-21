package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-011 — Detects LIKE expressions whose pattern starts with {@code %},
 * which prevents the database engine from using a B-tree index on that column.
 *
 * <p>Both forms are flagged:
 * <ul>
 *   <li>{@code LIKE '%value'}  — leading wildcard only</li>
 *   <li>{@code LIKE '%value%'} — leading AND trailing wildcard</li>
 * </ul>
 *
 * <p>When a pattern begins with {@code %} the engine cannot anchor the search
 * to the left of the value, forcing a full index or table scan.
 *
 * <p>Impact: HIGH — disables index usage on the filtered column.
 */
public class LeadingWildcardRule implements OptimizationRule {

    /**
     * Matches {@code LIKE '%...} where the literal after LIKE starts with %.
     * Handles both single-quoted and double-quoted literals, with optional spaces.
     */
    private static final Pattern LEADING_WILDCARD = Pattern.compile(
            "\\bLIKE\\s+(['\"])%",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getRuleId() {
        return "OPT-011";
    }

    @Override
    public String getDescription() {
        return "LIKE with a leading wildcard ('%value' or '%value%') disables index usage on that column";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        // AST-based detection: look for LIKE_EXPR nodes whose LITERAL child starts with %
        for (AstNode likeExpr : AstUtils.findNodes(ast, "LIKE_EXPR")) {
            for (AstNode child : likeExpr.getChildren()) {
                if ("LITERAL".equals(child.getNodeType()) && child.getValue() != null
                        && child.getValue().startsWith("%")) {
                    return true;
                }
            }
        }
        // Fallback: regex over raw SQL to catch patterns the AST may not model yet
        return LEADING_WILDCARD.matcher(query).find();
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "A LIKE pattern that begins with '%' (e.g. LIKE '%value' or LIKE '%value%') " +
                "forces the database to scan every row because it cannot use the column's B-tree index. " +
                "This is especially costly on large tables.",
                "WHERE name LIKE '%smith%'",
                "Consider a full-text index (FULLTEXT / GIN/GiST) and use MATCH AGAINST or @@, " +
                "or restructure the search so the pattern does not start with '%' (e.g. LIKE 'smith%').",
                "HIGH"
        );
    }
}
