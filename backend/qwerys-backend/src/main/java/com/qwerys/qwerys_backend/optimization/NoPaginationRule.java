package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.List;

/**
 * OPT-003 — Detects SELECT queries without a LIMIT clause that could return
 * an unbounded number of rows.
 *
 * <p>Impact: MEDIUM — production queries without pagination can exhaust memory
 * and overwhelm the application layer when tables grow large.
 */
public class NoPaginationRule implements OptimizationRule {

    @Override
    public String getRuleId() {
        return "OPT-003";
    }

    @Override
    public String getDescription() {
        return "Query has no LIMIT clause — could return millions of rows";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        if (!"SELECT_STATEMENT".equals(ast.getNodeType())) return false;

        // SQL Server uses TOP instead of LIMIT — skip if TOP is present
        boolean hasTop = ast.getChildren().stream()
                .anyMatch(c -> "TOP".equals(c.getNodeType()) || "TOP_PERCENT".equals(c.getNodeType()));
        if (hasTop) return false;

        return ast.getChildren().stream()
                .noneMatch(c -> "LIMIT".equals(c.getNodeType()));
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return apply(ast, query, null);
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query, List<String> schemaColumns) {
        String trimmed = query.stripTrailing();
        boolean hasSemi = trimmed.endsWith(";");
        String base = hasSemi ? trimmed.substring(0, trimmed.length() - 1).stripTrailing() : trimmed;
        if (schemaColumns != null && !schemaColumns.isEmpty()) {
            base = SchemaOptimizationSupport.expandSelectStar(base, schemaColumns);
        }
        String suggestion = hasSemi ? base + " LIMIT 100;" : base + " LIMIT 100";

        return new OptimizationSuggestion(
                getRuleId(),
                "This query has no LIMIT/TOP clause and could return an unbounded number of rows. " +
                "Add pagination to protect against excessive memory usage and slow response times.",
                query,
                suggestion,
                "MEDIUM"
        );
    }
}
