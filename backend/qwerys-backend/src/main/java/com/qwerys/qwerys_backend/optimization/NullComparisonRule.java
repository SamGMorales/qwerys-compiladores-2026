package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.List;

/**
 * OPT-006 — Detects {@code column = NULL} comparisons and corrects them to
 * {@code column IS NULL}.
 *
 * <p>Impact: HIGH — in SQL, {@code NULL = NULL} evaluates to UNKNOWN (not TRUE),
 * so a {@code = NULL} predicate never matches any row. This is a silent correctness
 * bug that also prevents index seeks on nullable columns.
 */
public class NullComparisonRule implements OptimizationRule {

    @Override
    public String getRuleId() {
        return "OPT-006";
    }

    @Override
    public String getDescription() {
        return "column = NULL never matches — use IS NULL instead";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        for (AstNode cmp : AstUtils.findNodes(ast, "COMPARISON")) {
            if ("=".equals(cmp.getValue()) && cmp.getChildren().size() >= 2) {
                AstNode right = cmp.getChildren().get(1);
                if ("LITERAL".equals(right.getNodeType())
                        && "NULL".equalsIgnoreCase(right.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        // Collect affected column names for a more informative message
        String columnHint = "column";
        List<AstNode> comparisons = AstUtils.findNodes(ast, "COMPARISON");
        for (AstNode cmp : comparisons) {
            if ("=".equals(cmp.getValue()) && cmp.getChildren().size() >= 2) {
                AstNode right = cmp.getChildren().get(1);
                if ("LITERAL".equals(right.getNodeType())
                        && "NULL".equalsIgnoreCase(right.getValue())) {
                    AstNode left = cmp.getChildren().get(0);
                    if (left.getValue() != null) {
                        columnHint = left.getValue();
                    }
                    break;
                }
            }
        }

        return new OptimizationSuggestion(
                getRuleId(),
                "In SQL, NULL = NULL evaluates to UNKNOWN, not TRUE. The predicate '" + columnHint +
                " = NULL' will never match any row — this is a silent correctness bug. " +
                "Use IS NULL (or IS NOT NULL) to test for null values.",
                columnHint + " = NULL",
                columnHint + " IS NULL",
                "HIGH"
        );
    }
}
