package com.qwerys.qwerys_backend.analyzer;

/**
 * A token produced by {@link ElasticsearchLexer}.
 */
public record ElasticsearchToken(
        EsTokenType type,
        String value,
        int line,
        int column
) {}
