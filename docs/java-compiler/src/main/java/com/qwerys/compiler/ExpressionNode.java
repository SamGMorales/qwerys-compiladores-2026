package com.qwerys.compiler;

/**
 * Nodo de expresion (identificador, numero o string literal)
 * Migrado de: class ExpressionNode en AST.h (C++)
 * Responsable: Mercedes Azucena Lopez Perez - Rama: feature/mercedes-lopez-parser-ast
 */
public class ExpressionNode extends ASTNode {
    public enum ExprType { IDENTIFIER, NUMBER, STRING }

    public ExprType type;
    public String value;

    public ExpressionNode(ExprType type, String value) {
        this.type = type;
        this.value = value;
    }

    @Override
    public void print(int indent) {
        String sp = " ".repeat(indent);
        switch (type) {
            case IDENTIFIER: System.out.println(sp + "Identifier: " + value); break;
            case NUMBER:     System.out.println(sp + "Number: " + value); break;
            case STRING:     System.out.println(sp + "String: '" + value + "'"); break;
        }
    }
}
