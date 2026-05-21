package com.qwerys.qwerys_backend.analyzer;

public enum EsTokenType {
    BRACE_OPEN, BRACE_CLOSE,
    BRACKET_OPEN, BRACKET_CLOSE,
    COLON, COMMA,
    STRING, NUMBER, BOOLEAN, NULL,
    QUERY_KEY, AGG_KEY,
    EOF, UNKNOWN
}
