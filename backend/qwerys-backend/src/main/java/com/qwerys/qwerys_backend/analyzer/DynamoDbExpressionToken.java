package com.qwerys.qwerys_backend.analyzer;

/**
 * A token produced by {@link DynamoDbExpressionLexer}.
 */
public record DynamoDbExpressionToken(
        DynamoDbExpressionTokenType type,
        String lexeme,
        int line,
        int column
) {}
