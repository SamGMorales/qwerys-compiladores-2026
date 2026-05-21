package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.List;
import java.util.regex.Pattern;

/**
 * OPT-CURSOR-LOOP — FETCH-based cursor iteration inside procedural loops ({@code LOOP},
 * {@code WHILE}/{@code FOR}) combined with row-by-row UPDATE/DELETE is usually slower than a
 * set-based {@code UPDATE}/{@code DELETE} with equivalent predicates (join or WHERE).
 */
public class CursorLoopRule implements OptimizationRule {

    private static final Pattern UPDATE_OR_DELETE = Pattern.compile(
            "\\b(UPDATE|DELETE)\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String getRuleId() {
        return "OPT-CURSOR-LOOP";
    }

    @Override
    public String getDescription() {
        return "Cursor FETCH inside LOOP/WHILE/FOR with UPDATE/DELETE — consider set-based DML";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        return anyLoopHasFetchWithRowDml(AstUtils.findNodes(ast, "LOOP_STATEMENT"))
                || anyLoopHasFetchWithRowDml(AstUtils.findNodes(ast, "WHILE_STATEMENT"))
                || anyLoopHasFetchWithRowDml(AstUtils.findNodes(ast, "FOR_STATEMENT"));
    }

    private static boolean anyLoopHasFetchWithRowDml(List<AstNode> loopNodes) {
        for (AstNode loop : loopNodes) {
            if (AstUtils.hasNodeType(loop, "FETCH_STATEMENT") && loopSubtreeHasRowDml(loop)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "A procedural loop that FETCHes a cursor and runs UPDATE or DELETE per iteration is often a row-by-row "
                        + "anti-pattern. Unless you need row-level logic, a single set-based UPDATE or DELETE with joins "
                        + "or subqueries is usually faster and holds fewer locks.",
                "LOOP/WHILE/FOR ... FETCH c INTO ...; UPDATE t SET ... WHERE ...; END LOOP;",
                "UPDATE t SET ... FROM ... WHERE <cursor predicates>;  -- or DELETE with equivalent filter",
                "MEDIUM"
        );
    }

    private static boolean loopSubtreeHasRowDml(AstNode node) {
        if ("UPDATE_STATEMENT".equals(node.getNodeType()) || "DELETE_STATEMENT".equals(node.getNodeType())) {
            return true;
        }
        if ("RAW_STATEMENT".equals(node.getNodeType())) {
            for (AstNode c : node.getChildren()) {
                if ("EXPRESSION".equals(c.getNodeType())
                        && c.getValue() != null
                        && UPDATE_OR_DELETE.matcher(c.getValue()).find()) {
                    return true;
                }
            }
        }
        for (AstNode c : node.getChildren()) {
            if (loopSubtreeHasRowDml(c)) {
                return true;
            }
        }
        return false;
    }
}
