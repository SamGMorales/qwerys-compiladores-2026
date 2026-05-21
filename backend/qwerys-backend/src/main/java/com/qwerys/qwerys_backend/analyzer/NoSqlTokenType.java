package com.qwerys.qwerys_backend.analyzer;

/**
 * Token kinds produced by {@link MongoDbLexer} for MongoDB shell-style queries.
 */
public enum NoSqlTokenType {

    FIND,
    AGGREGATE,
    MAPREDUCE,
    INSERT,
    UPDATE,
    DELETE,
    DROP,
    CREATE,
    /** {@code db.createCollection("name", options)} — first arg is the collection name. */
    CREATE_DB_COLLECTION,
    WATCH,
    /** Cursor / shell chain after the primary call, e.g. {@code .limit(100)} on {@code find()}. */
    CHAIN_METHOD,

    COLLECTION_NAME,
    FIELD_NAME,
    OPERATOR,
    BRACE_OPEN,
    BRACE_CLOSE,
    BRACKET_OPEN,
    BRACKET_CLOSE,
    COLON,
    COMMA,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
    EOF
}
