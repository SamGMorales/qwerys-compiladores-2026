package com.qwerys.qwerys_backend.analyzer.nosql;

import java.util.ArrayList;
import java.util.List;

/**
 * AST node for the Redis Lua subset parser. {@link #kind} uses the constants below.
 */
public final class LuaAstNode {

    public static final String CHUNK = "CHUNK";
    public static final String BLOCK = "BLOCK";
    public static final String FUNCTION_DEF = "FUNCTION_DEF";
    public static final String IF_STATEMENT = "IF_STATEMENT";
    public static final String WHILE_STATEMENT = "WHILE_STATEMENT";
    public static final String FOR_STATEMENT = "FOR_STATEMENT";
    public static final String ASSIGNMENT = "ASSIGNMENT";
    public static final String FUNCTION_CALL = "FUNCTION_CALL";
    public static final String TABLE_CONSTRUCTOR = "TABLE_CONSTRUCTOR";
    public static final String RETURN_STATEMENT = "RETURN_STATEMENT";
    public static final String BREAK_STATEMENT = "BREAK_STATEMENT";
    public static final String EXPR_STMT = "EXPR_STMT";
    public static final String LOCAL_DECL = "LOCAL_DECL";
    public static final String NAME = "NAME";
    public static final String LITERAL = "LITERAL";
    public static final String INDEX = "INDEX";
    public static final String FIELD = "FIELD";
    public static final String BINARY_EXPR = "BINARY_EXPR";
    public static final String UNARY_EXPR = "UNARY_EXPR";
    public static final String REPEAT_STATEMENT = "REPEAT_STATEMENT";

    private final String kind;
    private final int line;
    private final List<LuaAstNode> children = new ArrayList<>();
    private String text;
    /** For {@link #ASSIGNMENT}: {@code true} if introduced with {@code local}. */
    private boolean localAssignment;

    public LuaAstNode(String kind, int line) {
        this.kind = kind;
        this.line = line;
    }

    public String kind() {
        return kind;
    }

    public int line() {
        return line;
    }

    public List<LuaAstNode> children() {
        return children;
    }

    public String text() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean localAssignment() {
        return localAssignment;
    }

    public void setLocalAssignment(boolean localAssignment) {
        this.localAssignment = localAssignment;
    }

    public LuaAstNode addChild(LuaAstNode c) {
        if (c != null) {
            children.add(c);
        }
        return this;
    }
}
