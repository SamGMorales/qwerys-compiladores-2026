package com.qwerys.compiler;

/**
 * Tipos de tokens que reconoce el Lexer
 * Migrado de: Token.h (enum class TokenType en C++)
 * Responsable: Marjorie Samantha Giron Morales (Arquitecto)
 */
public enum TokenType {
    // Palabras clave SQL
    SELECT, FROM, WHERE,

    // Identificadores y literales
    IDENTIFIER, NUMBER, STRING,

    // Operadores de comparacion
    EQUAL,          // =
    GREATER,        // >
    LESS,           // <
    GREATER_EQUAL,  // >=
    LESS_EQUAL,     // <=
    NOT_EQUAL,      // \!=

    // Simbolos especiales
    ASTERISK,       // *
    COMMA,          // ,
    SEMICOLON,      // ;

    // Control
    END_OF_FILE,
    INVALID
}
