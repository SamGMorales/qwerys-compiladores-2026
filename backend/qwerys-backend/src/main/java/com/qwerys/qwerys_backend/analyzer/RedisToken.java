package com.qwerys.qwerys_backend.analyzer;

/**
 * A single token emitted by {@link RedisLexer}.
 */
public record RedisToken(
        RedisTokenType type,
        String value,
        int line,
        int column
) {}
