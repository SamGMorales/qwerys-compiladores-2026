package com.qwerys.qwerys_backend.analyzer;

/**
 * Token kinds for {@link CqlLexer} (Apache Cassandra CQL surface syntax).
 */
public enum CqlTokenType {
    KEYWORD,
    IDENTIFIER,
    NUMBER,
    /** Standard UUID literal {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}. */
    UUID_LITERAL,
    BOOLEAN,
    STRING,
    OPERATOR,
    COMMA,
    SEMICOLON,
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    LEFT_BRACE,
    RIGHT_BRACE,
    LESS_THAN,
    GREATER_THAN,
    ASTERISK,
    DOT,
    EOF,
    UNKNOWN
}
