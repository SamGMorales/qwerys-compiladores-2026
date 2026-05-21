package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.List;
import java.util.regex.Pattern;

/**
 * OPT-PROC-001 - Cursor-driven iteration combined with row-level UPDATE/DELETE often yields poor
 * throughput versus a single set-based statement.
 */
public class CursorReplaceableByUpdateRule implements OptimizationRule {

    private static final Pattern UPDATE_OR_DELETE = Pattern.compile(
            "\\b(UPDATE|DELETE)\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String getRuleId() {
        return "OPT-PROC-001";
    }

    @Override
    public String getDescription() {
        return "Cursor iteration with UPDATE/DELETE - prefer set-based UPDATE/DELETE";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        List<AstNode> forLoops = AstUtils.findNodes(ast, "FOR_STATEMENT");
        return anyLoopHasFetchWithRowDml(AstUtils.findNodes(ast, "LOOP_STATEMENT"))
                || anyLoopHasFetchWithRowDml(AstUtils.findNodes(ast, "WHILE_STATEMENT"))
                || anyLoopHasFetchWithRowDml(forLoops)
                || anyLoopHasFetchWithRowDml(AstUtils.findNodes(ast, "REPEAT_STATEMENT"))
                || anyForCursorLoopHasRowDml(forLoops);
    }

    private static boolean anyLoopHasFetchWithRowDml(List<AstNode> loopNodes) {
        for (AstNode loop : loopNodes) {
            if (AstUtils.hasNodeType(loop, "FETCH_STATEMENT") && loopSubtreeHasRowDml(loop)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Implicit cursor loops ({@code FOR r IN (SELECT …)}): no {@code FETCH_STATEMENT} nodes, but still row-at-a-time
     * iteration — combine with UPDATE/DELETE in the body matches OPT-PROC-001 intent.
     */
    private static boolean anyForCursorLoopHasRowDml(List<AstNode> forNodes) {
        for (AstNode forStmt : forNodes) {
            if (ProceduralOptimizationSupport.forLoopHasCursorDriver(forStmt) && loopSubtreeHasRowDml(forStmt)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "A cursor loop that FETCHes rows and applies UPDATE or DELETE per iteration is usually slower "
                        + "and locks more resources than one set-based UPDATE or DELETE with equivalent predicates "
                        + "(joins, WHERE, or MERGE).",
                "LOOP/FETCH cursor INTO variables; UPDATE or DELETE per iteration;",
                "Single set-based UPDATE or DELETE with the same predicates (FROM/JOIN/WHERE/MERGE).",
                "HIGH");
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
