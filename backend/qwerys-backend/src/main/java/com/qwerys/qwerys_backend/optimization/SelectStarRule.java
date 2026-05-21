package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.List;

/**
 * OPT-001 — Detects {@code SELECT *} and recommends specifying explicit columns.
 *
 * <p>Impact: HIGH — fetching all columns increases I/O, memory, and network transfer,
 * and prevents the optimizer from using covering indexes.
 */
public class SelectStarRule implements OptimizationRule {

    @Override
    public String getRuleId() {
        return "OPT-001";
    }

    @Override
    public String getDescription() {
        return "Avoid SELECT * — specify only the columns you need";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        List<AstNode> columnLists = AstUtils.findNodes(ast, "COLUMN_LIST");
        for (AstNode list : columnLists) {
            for (AstNode child : list.getChildren()) {
                if ("COLUMN_REF".equals(child.getNodeType()) && "*".equals(child.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return apply(ast, query, null);
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query, List<String> schemaColumns) {
        String columnList = SchemaOptimizationSupport.formatColumnList(schemaColumns);
        return new OptimizationSuggestion(
                getRuleId(),
                "SELECT * fetches every column, including unused ones. Specify only the columns " +
                "you need to reduce I/O, memory consumption, network transfer, and to allow the " +
                "optimizer to use covering indexes.",
                "SELECT *",
                "SELECT " + columnList,
                "HIGH"
        );
    }
}
