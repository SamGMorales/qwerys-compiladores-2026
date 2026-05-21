package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared traversal helpers for procedural optimization rules (loops, raw SQL fragments).
 */
final class ProceduralOptimizationSupport {

    private static final List<String> LOOP_TYPES = List.of(
            "LOOP_STATEMENT",
            "WHILE_STATEMENT",
            "FOR_STATEMENT",
            "REPEAT_STATEMENT");

    private ProceduralOptimizationSupport() {}

    static List<AstNode> allLoops(AstNode root) {
        List<AstNode> out = new ArrayList<>();
        for (String t : LOOP_TYPES) {
            out.addAll(AstUtils.findNodes(root, t));
        }
        return out;
    }

    /**
     * Raw {@code EXPRESSION} payloads inside {@code RAW_STATEMENT} nodes, descending the loop subtree
     * but skipping {@code FOR_CURSOR} branches (driver query is not loop-body work per iteration).
     */
    static List<String> rawExpressionsInLoopBodySkippingForCursor(AstNode loopRoot) {
        List<String> out = new ArrayList<>();
        visitSkippingForCursor(loopRoot, n -> {
            if (!"RAW_STATEMENT".equals(n.getNodeType())) {
                return;
            }
            for (AstNode c : n.getChildren()) {
                if ("EXPRESSION".equals(c.getNodeType()) && c.getValue() != null && !c.getValue().isBlank()) {
                    out.add(c.getValue());
                }
            }
        });
        return out;
    }

    /** Every raw expression under {@code loopRoot}, including inside {@code FOR_CURSOR}. */
    static List<String> allRawExpressions(AstNode loopRoot) {
        List<String> out = new ArrayList<>();
        collectRawExpressions(loopRoot, out);
        return out;
    }

    private static void collectRawExpressions(AstNode node, List<String> out) {
        if ("RAW_STATEMENT".equals(node.getNodeType())) {
            for (AstNode c : node.getChildren()) {
                if ("EXPRESSION".equals(c.getNodeType()) && c.getValue() != null && !c.getValue().isBlank()) {
                    out.add(c.getValue());
                }
            }
        }
        for (AstNode ch : node.getChildren()) {
            collectRawExpressions(ch, out);
        }
    }

    private static void visitSkippingForCursor(AstNode node, java.util.function.Consumer<AstNode> visitor) {
        if ("FOR_CURSOR".equals(node.getNodeType())) {
            return;
        }
        visitor.accept(node);
        for (AstNode ch : node.getChildren()) {
            visitSkippingForCursor(ch, visitor);
        }
    }

    static boolean forLoopHasCursorDriver(AstNode forStmt) {
        if (!"FOR_STATEMENT".equals(forStmt.getNodeType())) {
            return false;
        }
        for (AstNode ch : forStmt.getChildren()) {
            if ("FOR_CURSOR".equals(ch.getNodeType())) {
                return true;
            }
        }
        return false;
    }

    static String normalizeSqlFragment(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    static boolean rollbackAstIsToSavepoint(AstNode rollbackStmt) {
        for (AstNode ch : rollbackStmt.getChildren()) {
            if ("SAVEPOINT_REF".equals(ch.getNodeType())) {
                return true;
            }
        }
        return false;
    }
}
