package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OPT-002 — Detects WHERE filters on columns that commonly require an index
 * (email, user_id, username, etc.) and suggests creating one if it may be absent.
 *
 * <p>Impact: HIGH — filtering large tables on un-indexed columns causes full table scans.
 */
public class MissingIndexRule implements OptimizationRule {

    private static final Set<String> COMMONLY_INDEXED = Set.of(
            "email", "user_id", "username", "phone", "name",
            "created_at", "updated_at", "status", "type",
            "category_id", "product_id", "order_id", "customer_id",
            "account_id", "tenant_id", "session_id", "token"
    );

    @Override
    public String getRuleId() {
        return "OPT-002";
    }

    @Override
    public String getDescription() {
        return "WHERE filter on a commonly un-indexed column — consider adding an index";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        return !candidateColumns(ast).isEmpty();
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        List<String> cols = candidateColumns(ast);
        String colList = String.join(", ", cols);
        String firstCol = cols.get(0);
        String tableName = findPrimaryTableName(ast);
        if (tableName == null || tableName.isBlank()) {
            tableName = "your_table";
        }
        String originalFragment = findComparisonFragment(ast, firstCol);
        if (originalFragment == null || originalFragment.isBlank()) {
            originalFragment = "WHERE " + firstCol + " = ?";
        }
        return new OptimizationSuggestion(
                getRuleId(),
                "Column(s) [" + colList + "] are used in WHERE but may lack an index. " +
                "Without an index the database performs a full table scan on every query.",
                originalFragment,
                "CREATE INDEX idx_" + firstCol + " ON " + tableName + "(" + firstCol + ");",
                "HIGH"
        );
    }

    private List<String> candidateColumns(AstNode ast) {
        List<String> result = new ArrayList<>();
        for (AstNode where : AstUtils.findNodes(ast, "WHERE_CLAUSE")) {
            for (AstNode cmp : AstUtils.findNodes(where, "COMPARISON")) {
                if (cmp.getChildren().isEmpty()) continue;
                AstNode left = cmp.getChildren().get(0);
                if (!"COLUMN_REF".equals(left.getNodeType()) || left.getValue() == null) continue;

                String colName = left.getValue().toLowerCase();
                int dot = colName.lastIndexOf('.');
                if (dot >= 0) colName = colName.substring(dot + 1);

                if (COMMONLY_INDEXED.contains(colName) && !result.contains(colName)) {
                    result.add(colName);
                }
            }
        }
        return result;
    }

    /** Best-effort: first TABLE_REF in the statement (handles WITH_SELECT_STATEMENT too). */
    private String findPrimaryTableName(AstNode ast) {
        AstNode stmt = ast;
        if ("WITH_SELECT_STATEMENT".equals(ast.getNodeType())) {
            for (AstNode child : ast.getChildren()) {
                if ("SELECT_STATEMENT".equals(child.getNodeType())) {
                    stmt = child;
                    break;
                }
            }
        }
        List<AstNode> refs = AstUtils.findNodes(stmt, "TABLE_REF");
        if (refs.isEmpty()) {
            return null;
        }
        String raw = refs.get(0).getValue();
        if (raw == null) return null;
        String t = raw.strip();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0 && dot < t.length() - 1) {
            t = t.substring(dot + 1);
        }
        return t;
    }

    /** Build the real "WHERE col OP value" fragment from the AST for the bare column name. */
    private String findComparisonFragment(AstNode ast, String bareColName) {
        for (AstNode where : AstUtils.findNodes(ast, "WHERE_CLAUSE")) {
            for (AstNode cmp : AstUtils.findNodes(where, "COMPARISON")) {
                if (cmp.getChildren().size() < 2) continue;
                AstNode left = cmp.getChildren().get(0);
                if (!"COLUMN_REF".equals(left.getNodeType()) || left.getValue() == null) continue;
                String leftCol = left.getValue();
                String bare = leftCol;
                int dot = bare.lastIndexOf('.');
                if (dot >= 0 && dot < bare.length() - 1) {
                    bare = bare.substring(dot + 1);
                }
                if (!bare.equalsIgnoreCase(bareColName)) continue;

                String op = "=";
                String rhs = "?";
                if (cmp.getChildren().size() >= 3) {
                    AstNode opNode = cmp.getChildren().get(1);
                    if (opNode != null && opNode.getValue() != null && !opNode.getValue().isBlank()) {
                        op = opNode.getValue();
                    }
                    AstNode right = cmp.getChildren().get(2);
                    rhs = renderLiteralOrRef(right);
                } else {
                    // Some parsers store op as the comparison node's value
                    if (cmp.getValue() != null && !cmp.getValue().isBlank()) {
                        op = cmp.getValue();
                    }
                    AstNode right = cmp.getChildren().get(1);
                    rhs = renderLiteralOrRef(right);
                }
                return "WHERE " + leftCol + " " + op + " " + rhs;
            }
        }
        return null;
    }

    private String renderLiteralOrRef(AstNode node) {
        if (node == null) return "?";
        String v = node.getValue();
        if (v == null || v.isBlank()) return "?";
        String type = node.getNodeType();
        if ("STRING_LITERAL".equals(type)) {
            String unquoted = v;
            if (unquoted.length() >= 2 && unquoted.startsWith("'") && unquoted.endsWith("'")) {
                return v;
            }
            return "'" + v + "'";
        }
        return v;
    }
}
