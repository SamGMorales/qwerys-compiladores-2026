package com.qwerys.qwerys_backend.analyzer;

/**
 * A single token emitted by {@link MongoDbLexer}.
 */
public record NoSqlToken(
        NoSqlTokenType type,
        String value,
        int line,
        int column
) {}
