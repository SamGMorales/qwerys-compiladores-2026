package com.qwerys.qwerys_backend.analyzer.nosql;

import java.util.ArrayList;
import java.util.List;

/**
 * AST for the Painless subset parser. {@link #kind()} uses the constants below.
 */
public final class PainlessAstNode {

    public static final String CHUNK = "CHUNK";
    public static final String BLOCK = "BLOCK";
    public static final String FUNCTION_DEF = "FUNCTION_DEF";
    public static final String IF = "IF";
    public static final String WHILE = "WHILE";
    public static final String FOR = "FOR";
    public static final String ASSIGNMENT = "ASSIGNMENT";
    public static final String METHOD_CALL = "METHOD_CALL";
    public static final String RETURN = "RETURN";
    public static final String EXPR_STMT = "EXPR_STMT";
    public static final String NAME = "NAME";
    public static final String LITERAL = "LITERAL";
    public static final String BINARY_EXPR = "BINARY_EXPR";
    public static final String UNARY_EXPR = "UNARY_EXPR";
    public static final String INDEX = "INDEX";
    public static final String FIELD = "FIELD";

    private final String kind;
    private final int line;
    private final List<PainlessAstNode> children = new ArrayList<>();
    private String text;

    public PainlessAstNode(String kind, int line) {
        this.kind = kind;
        this.line = line;
    }

    public String kind() {
        return kind;
    }

    public int line() {
        return line;
    }

    public List<PainlessAstNode> children() {
        return children;
    }

    public String text() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public PainlessAstNode addChild(PainlessAstNode c) {
        if (c != null) {
            children.add(c);
        }
        return this;
    }
}
