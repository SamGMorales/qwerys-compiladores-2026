package com.qwerys.qwerys_backend.analyzer.nosql;

public record PainlessToken(PainlessTokenType type, String lexeme, int line, int column) {
}
