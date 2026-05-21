package com.qwerys.qwerys_backend.analyzer;

public enum TokenType {
    // Literals and identifiers
    KEYWORD,
    IDENTIFIER,
    NUMBER,
    STRING,

    // Operators
    OPERATOR,
    EQUALS,
    NOT_EQUALS,
    LESS_THAN,
    GREATER_THAN,
    LESS_EQUAL,
    GREATER_EQUAL,

    // Logical
    AND,
    OR,
    NOT,

    // Punctuation
    COMMA,
    SEMICOLON,
    LEFT_PAREN,
    RIGHT_PAREN,
    ASTERISK,
    DOT,
    COLON,

    // Special
    WHITESPACE,
    EOF,
    UNKNOWN
}
