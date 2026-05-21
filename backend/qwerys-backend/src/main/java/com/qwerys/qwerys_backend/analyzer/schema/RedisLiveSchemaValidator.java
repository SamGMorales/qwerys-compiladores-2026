package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.RedisLexer;
import com.qwerys.qwerys_backend.analyzer.RedisToken;
import com.qwerys.qwerys_backend.analyzer.RedisTokenType;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.StatementSplitter;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live-schema validation for Redis commands (keys and hash fields from live schema).
 */
public final class RedisLiveSchemaValidator {

    private static final Set<String> KEY_COMMANDS = Set.of(
            "GET", "SET", "DEL", "EXISTS", "TYPE", "TTL", "PTTL", "PERSIST", "EXPIRE", "EXPIREAT",
            "PEXPIRE", "PEXPIREAT", "INCR", "INCRBY", "DECR", "DECRBY", "APPEND", "GETRANGE", "STRLEN",
            "HGET", "HSET", "HDEL", "HEXISTS", "HGETALL", "HKEYS", "HVALS", "HLEN", "HMGET", "HMSET",
            "LLEN", "LPOP", "RPOP", "LPUSH", "RPUSH", "LRANGE", "LINDEX", "SADD", "SREM", "SMEMBERS",
            "SISMEMBER", "SCARD", "ZADD", "ZREM", "ZSCORE", "ZRANK", "ZRANGE", "ZCARD", "XADD", "XREAD");

    private RedisLiveSchemaValidator() {
    }

    public static void validate(String raw, DatabaseSchema schema, Locale ui, List<SemanticError> findings) {
        if (raw == null || raw.isBlank() || schema == null) {
            return;
        }
        Map<String, TableSchema> tableIndex = SchemaValidationSupport.buildTableIndex(schema);
        if (tableIndex.isEmpty()) {
            return;
        }
        SchemaEntityLabels labels = SchemaEntityLabels.REDIS;
        Set<String> reportedKeys = new HashSet<>();
        Set<String> reportedFields = new HashSet<>();
        for (String line : StatementSplitter.split(raw, SqlDialect.GENERIC)) {
            String one = line.strip();
            if (one.isEmpty()) {
                continue;
            }
            try {
                List<RedisToken> tokens = new RedisLexer(one).tokenize();
                String cmd = commandName(tokens);
                if (cmd == null || !KEY_COMMANDS.contains(cmd)) {
                    continue;
                }
                String key = firstKey(tokens);
                if (key == null) {
                    continue;
                }
                SchemaValidationSupport.reportMissingEntity(key, tableIndex, reportedKeys, findings, ui, labels);
                if (!SchemaValidationSupport.tableExists(key, tableIndex)) {
                    continue;
                }
                for (String field : allFields(tokens)) {
                    SchemaValidationSupport.reportMissingField(
                            field, key, tableIndex, reportedFields, findings, ui, labels);
                }
            } catch (RedisLexer.LexException ignored) {
            }
        }
    }

    private static List<String> allFields(List<RedisToken> tokens) {
        List<String> fields = new ArrayList<>();
        for (RedisToken t : tokens) {
            if (t.type() == RedisTokenType.FIELD) {
                fields.add(t.value());
            }
        }
        return fields;
    }

    private static String commandName(List<RedisToken> tokens) {
        for (RedisToken t : tokens) {
            if (t.type() == RedisTokenType.COMMAND
                    || t.type() == RedisTokenType.STREAM_COMMAND
                    || t.type() == RedisTokenType.GEO_COMMAND
                    || t.type() == RedisTokenType.BITFIELD_COMMAND
                    || t.type() == RedisTokenType.HYPERLOGLOG_COMMAND) {
                return t.value().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static String firstKey(List<RedisToken> tokens) {
        for (RedisToken t : tokens) {
            if (t.type() == RedisTokenType.KEY) {
                return t.value();
            }
        }
        return null;
    }
}
