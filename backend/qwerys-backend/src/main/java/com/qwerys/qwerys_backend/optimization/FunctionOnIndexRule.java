package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.List;

/**
 * OPT-007 — Detects function calls wrapping a column on the left-hand side of a
 * WHERE comparison (e.g. {@code WHERE UPPER(email) = 'ALICE@EXAMPLE.COM'}).
 *
 * <p>Impact: HIGH — wrapping a column in a function prevents the engine from using
 * a B-tree index on that column, forcing a full table scan where every row is
 * evaluated individually.
 */
public class FunctionOnIndexRule implements OptimizationRule {

    @Override
    public String getRuleId() {
        return "OPT-007";
    }

    @Override
    public String getDescription() {
        return "Function applied to a column in WHERE — index on that column cannot be used";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        for (AstNode where : AstUtils.findNodes(ast, "WHERE_CLAUSE")) {
            for (AstNode cmp : AstUtils.findNodes(where, "COMPARISON")) {
                if (!cmp.getChildren().isEmpty()
                        && "FUNCTION_CALL".equals(cmp.getChildren().get(0).getNodeType())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        String funcName = extractFunctionName(ast);
        return new OptimizationSuggestion(
                getRuleId(),
                "Wrapping a column in " + funcName + "(...) inside WHERE prevents the optimizer " +
                "from using any B-tree index on that column. Either apply the function to the " +
                "constant side, store a pre-computed column, or create a functional/expression index.",
                "WHERE " + funcName + "(column) = 'value'",
                "WHERE column = 'normalized_value'  -- or use a functional index",
                "HIGH"
        );
    }

    private String extractFunctionName(AstNode ast) {
        for (AstNode where : AstUtils.findNodes(ast, "WHERE_CLAUSE")) {
            List<AstNode> comparisons = AstUtils.findNodes(where, "COMPARISON");
            for (AstNode cmp : comparisons) {
                if (!cmp.getChildren().isEmpty()) {
                    AstNode left = cmp.getChildren().get(0);
                    if ("FUNCTION_CALL".equals(left.getNodeType()) && left.getValue() != null) {
                        return left.getValue().toUpperCase();
                    }
                }
            }
        }
        return "FUNCTION";
    }
}
