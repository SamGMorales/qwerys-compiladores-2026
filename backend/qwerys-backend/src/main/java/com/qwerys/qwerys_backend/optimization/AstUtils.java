package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Package-private utility for traversing {@link AstNode} trees.
 * All methods are pure and do not modify the tree.
 */
final class AstUtils {

    private AstUtils() {}

    /**
     * Returns every node in the subtree rooted at {@code root} whose
     * {@link AstNode#getNodeType()} equals {@code nodeType} (exact match).
     */
    static List<AstNode> findNodes(AstNode root, String nodeType) {
        List<AstNode> results = new ArrayList<>();
        collect(root, nodeType, results);
        return results;
    }

    /** Convenience method — returns {@code true} if at least one matching node exists. */
    static boolean hasNodeType(AstNode root, String nodeType) {
        return !findNodes(root, nodeType).isEmpty();
    }

    private static void collect(AstNode node, String type, List<AstNode> out) {
        if (type.equals(node.getNodeType())) {
            out.add(node);
        }
        for (AstNode child : node.getChildren()) {
            collect(child, type, out);
        }
    }
}
