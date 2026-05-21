package com.qwerys.qwerys_backend.analyzer;

/**
 * Token kinds for DynamoDB Expression Syntax (condition, projection, update).
 */
public enum DynamoDbExpressionTokenType {
    EOF,
    /** Placeholder for attribute name, e.g. {@code #a}. */
    ATTR_NAME,
    /** Placeholder for value, e.g. {@code :v}. */
    ATTR_VALUE,
    IDENTIFIER,
    NUMBER,
    AND,
    OR,
    NOT,
    BETWEEN,
    IN,
    SET,
    REMOVE,
    ADD,
    DELETE,
    LPAREN,
    RPAREN,
    LBRACKET,
    RBRACKET,
    COMMA,
    DOT,
    /** One of {@code =}, {@code <>}, {@code !=}, {@code <}, {@code >}, {@code <=}, {@code >=}. */
    OPERATOR,
    /** Unrecognized single character (caller may flag). */
    UNKNOWN
}
