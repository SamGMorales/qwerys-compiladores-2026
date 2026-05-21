package com.qwerys.compiler;

/**
 * Nodo de condicion WHERE (izquierda operador derecha)
 * Migrado de: class ConditionNode en AST.h (C++)
 * Responsable: Mercedes Azucena Lopez Perez - Rama: feature/mercedes-lopez-parser-ast
 */
public class ConditionNode extends ASTNode {
    public ExpressionNode left;
    public CompOperator op;
    public ExpressionNode right;

    public ConditionNode(ExpressionNode left, CompOperator op, ExpressionNode right) {
        this.left = left; this.op = op; this.right = right;
    }

    @Override
    public void print(int indent) {
        String sp = " ".repeat(indent);
        System.out.println(sp + "Condition:");
        System.out.println(sp + "  Left:"); left.print(indent + 4);
        System.out.println(sp + "  Operator: " + op.toSymbol());
        System.out.println(sp + "  Right:"); right.print(indent + 4);
    }
}
