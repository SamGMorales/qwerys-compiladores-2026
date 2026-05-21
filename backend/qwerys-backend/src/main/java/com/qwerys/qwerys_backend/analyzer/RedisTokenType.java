package com.qwerys.qwerys_backend.analyzer;

/**
 * Token kinds produced by {@link RedisLexer} for inline Redis commands.
 */
public enum RedisTokenType {

    COMMAND,
    /** Stream commands: XADD, XREAD, XREADGROUP, XRANGE, … */
    STREAM_COMMAND,
    /** MULTI / EXEC / DISCARD / WATCH / UNWATCH */
    TRANSACTION_COMMAND,
    /** PUBLISH, SUBSCRIBE, PSUBSCRIBE, PUBSUB, … */
    PUBSUB_COMMAND,
    /** GEOADD, GEORADIUS, GEOSEARCH, … */
    GEO_COMMAND,
    /** PFADD, PFCOUNT, PFMERGE */
    HYPERLOGLOG_COMMAND,
    /** EVAL, EVALSHA, SCRIPT */
    SCRIPT_COMMAND,
    /** BITFIELD, BITCOUNT, BITOP, … */
    BITFIELD_COMMAND,

    KEY,
    VALUE,
    FIELD,
    EXPIRY_FLAG,
    NUMBER,
    STRING,
    PATTERN,
    EOF,
    UNKNOWN
}
