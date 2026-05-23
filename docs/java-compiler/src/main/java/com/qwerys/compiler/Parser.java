package com.qwerys.compiler;

import java.util.List;

/**
 * FASE 3 - Analizador Sintactico (Parser Descendente Recursivo)
 * Migrado de: Parser.h + Parser.cpp (C++)
 * Responsable: Mercedes Azucena Lopez Perez - Rama: feature/mercedes-lopez-parser-ast
 */
public class Parser {
    private List<Token> tokens;
    private int position;
    private Token currentToken;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.position = 0;
        this.currentToken = tokens.isEmpty() ? new Token() : tokens.get(0);
    }

    private void advance() {
        position++;
        if (position < tokens.size()) currentToken = tokens.get(position);
    }

    private boolean check(TokenType type) { return currentToken.type == type; }

    private void expect(TokenType type) {
        if (!check(type)) error("Se esperaba " + type + " pero se encontro " + currentToken.type);
        advance();
    }

    private void error(String msg) {
        throw new RuntimeException("Error sintactico L" + currentToken.line + ":C" + currentToken.column + " - " + msg);
    }

    private CompOperator tokenToOperator(TokenType t) {
        switch (t) {
            case EQUAL:         return CompOperator.EQUAL;
            case GREATER:       return CompOperator.GREATER;
            case LESS:          return CompOperator.LESS;
            case GREATER_EQUAL: return CompOperator.GREATER_EQUAL;
            case LESS_EQUAL:    return CompOperator.LESS_EQUAL;
            case NOT_EQUAL:     return CompOperator.NOT_EQUAL;
            default: error("Operador invalido"); return CompOperator.EQUAL;
        }
    }

    private ExpressionNode parseExpression() {
        if (check(TokenType.IDENTIFIER)) { String v = currentToken.value; advance(); return new ExpressionNode(ExpressionNode.ExprType.IDENTIFIER, v); }
        if (check(TokenType.NUMBER))     { String v = currentToken.value; advance(); return new ExpressionNode(ExpressionNode.ExprType.NUMBER, v); }
        if (check(TokenType.STRING))     { String v = currentToken.value; advance(); return new ExpressionNode(ExpressionNode.ExprType.STRING, v); }
        error("Se esperaba expresion"); return null;
    }

    private ConditionNode parseCondition() {
        ExpressionNode left = parseExpression();
        if (!check(TokenType.EQUAL) && !check(TokenType.GREATER) && !check(TokenType.LESS) &&
            !check(TokenType.GREATER_EQUAL) && !check(TokenType.LESS_EQUAL) && !check(TokenType.NOT_EQUAL))
            error("Se esperaba operador de comparacion");
        CompOperator op = tokenToOperator(currentToken.type);
        advance();
        return new ConditionNode(left, op, parseExpression());
    }

    private void parseColumns(SelectNode node) {
        if (check(TokenType.ASTERISK)) { node.selectAll = true; advance(); return; }
        if (!check(TokenType.IDENTIFIER)) error("Se esperaba columna o '*'");
        node.columns.add(currentToken.value); advance();
        while (check(TokenType.COMMA)) {
            advance();
            if (!check(TokenType.IDENTIFIER)) error("Se esperaba nombre de columna");
            node.columns.add(currentToken.value); advance();
        }
    }

    public SelectNode parse() {
        SelectNode node = new SelectNode();
        expect(TokenType.SELECT);
        parseColumns(node);
        expect(TokenType.FROM);
        if (!check(TokenType.IDENTIFIER)) error("Se esperaba nombre de tabla");
        node.tableName = currentToken.value; advance();
        if (check(TokenType.WHERE)) { advance(); node.whereCondition = parseCondition(); }
        if (check(TokenType.SEMICOLON)) advance();
        if (!check(TokenType.END_OF_FILE)) error("Se esperaba fin de archivo");
        return node;
    }
}
