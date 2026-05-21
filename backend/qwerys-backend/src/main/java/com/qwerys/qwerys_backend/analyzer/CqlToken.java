package com.qwerys.qwerys_backend.analyzer;

/**
 * A single token emitted by {@link CqlLexer}.
 */
public record CqlToken(CqlTokenType type, String value, int line, int column) {}
