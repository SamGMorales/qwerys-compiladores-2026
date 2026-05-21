package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.List;

public class AstNode {

    private final String nodeType;
    private final String value;
    private final List<AstNode> children;

    public AstNode(String nodeType) {
        this(nodeType, null);
    }

    public AstNode(String nodeType, String value) {
        this.nodeType = nodeType;
        this.value = value;
        this.children = new ArrayList<>();
    }

    public void addChild(AstNode child) {
        children.add(child);
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getValue() {
        return value;
    }

    public List<AstNode> getChildren() {
        return List.copyOf(children);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        buildString(sb, 0);
        return sb.toString();
    }

    private void buildString(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth)).append(nodeType);
        if (value != null) {
            sb.append("(").append(value).append(")");
        }
        sb.append("\n");
        for (AstNode child : children) {
            child.buildString(sb, depth + 1);
        }
    }
}
