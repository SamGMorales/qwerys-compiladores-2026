package com.qwerys.qwerys_backend.analyzer;

public record Token(
        TokenType type,
        String value,
        int line,
        int column
) {}
