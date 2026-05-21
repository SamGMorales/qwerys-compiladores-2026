package com.qwerys.qwerys_backend.adapter;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.commands.ProtocolCommand;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Redis access via Jedis (keys as logical "tables" for QWERYS metadata).
 */
public final class RedisAdapter implements DatabaseAdapter {

    private static final String DB_TYPE = "redis";
    private static final int MAX_KEYS_TABLES = 100;
    private static final int MAX_KEYS_SCHEMA = 20;
    private static final int MAX_HASH_FIELDS_PER_KEY = 128;

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public boolean testConnection(DatabaseConfig config) {
        try (JedisPool pool = createPool(config);
                Jedis jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> getTableNames(DatabaseConfig config) {
        try (JedisPool pool = createPool(config);
                Jedis jedis = pool.getResource()) {
            List<String> keys = new ArrayList<>(jedis.keys("*"));
            if (keys.size() > MAX_KEYS_TABLES) {
                return new ArrayList<>(keys.subList(0, MAX_KEYS_TABLES));
            }
            return keys;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<String> getColumnNames(DatabaseConfig config, String tableName) {
        return Collections.emptyList();
    }

    @Override
    public Map<String, String> getColumnTypes(DatabaseConfig config, String tableName) {
        return Collections.emptyMap();
    }

    @Override
    public boolean tableExists(DatabaseConfig config, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        try (JedisPool pool = createPool(config);
                Jedis jedis = pool.getResource()) {
            return jedis.exists(tableName);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public QueryExecutionResult executeQuery(DatabaseConfig config, String query) {
        long start = System.currentTimeMillis();
        if (query == null) {
            return failure(start, "Query is null");
        }
        String line = query.strip();
        if (line.isEmpty()) {
            return failure(start, "Empty query");
        }
        try (JedisPool pool = createPool(config);
                Jedis jedis = pool.getResource()) {
            String[] tokens = tokenize(line);
            if (tokens.length == 0) {
                return failure(start, "No command");
            }
            ProtocolCommand cmd = rawCommand(tokens[0]);
            String[] args = new String[tokens.length - 1];
            System.arraycopy(tokens, 1, args, 0, args.length);
            Object out = jedis.sendCommand(cmd, args);
            List<Map<String, Object>> rows =
                    List.of(new LinkedHashMap<>(Map.of("result", stringifyRedisResult(out))));
            return success(start, rows, 0);
        } catch (Exception e) {
            return failure(start, e.getMessage());
        }
    }

    @Override
    public DatabaseSchema getSchema(DatabaseConfig config) {
        DatabaseSchema schema = new DatabaseSchema();
        schema.setDbType(DB_TYPE);
        schema.setDatabaseName(config.database() != null ? config.database() : "");
        List<TableSchema> tables = new ArrayList<>();
        try (JedisPool pool = createPool(config);
                Jedis jedis = pool.getResource()) {
            List<String> keys = new ArrayList<>(jedis.keys("*"));
            int limit = Math.min(keys.size(), MAX_KEYS_SCHEMA);
            for (int i = 0; i < limit; i++) {
                String key = keys.get(i);
                String type = safeType(jedis, key);
                List<ColumnSchema> columns = new ArrayList<>();
                ColumnSchema typeCol = new ColumnSchema();
                typeCol.setColumnName("redisType");
                typeCol.setDataType(type);
                typeCol.setNullable(true);
                typeCol.setPrimaryKey(false);
                typeCol.setDefaultValue(null);
                columns.add(typeCol);
                if ("hash".equalsIgnoreCase(type)) {
                    int fieldLimit = 0;
                    for (String field : jedis.hkeys(key)) {
                        if (fieldLimit++ >= MAX_HASH_FIELDS_PER_KEY) {
                            break;
                        }
                        ColumnSchema fc = new ColumnSchema();
                        fc.setColumnName(field);
                        fc.setDataType("hash_field");
                        fc.setNullable(true);
                        fc.setPrimaryKey(false);
                        fc.setDefaultValue(null);
                        columns.add(fc);
                    }
                }
                TableSchema ts = new TableSchema();
                ts.setTableName(key);
                ts.setColumns(columns);
                ts.setPrimaryKeys(List.of());
                ts.setForeignKeys(List.of());
                tables.add(ts);
            }
        } catch (Exception e) {
            schema.setTables(List.of());
            return schema;
        }
        schema.setTables(tables);
        return schema;
    }

    private static String safeType(Jedis jedis, String key) {
        try {
            return jedis.type(key);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String[] tokenize(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                    parts.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                    parts.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    parts.add(cur.toString());
                    cur.setLength(0);
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            cur.append(c);
        }
        if (cur.length() > 0) {
            parts.add(cur.toString());
        }
        return parts.toArray(new String[0]);
    }

    private static ProtocolCommand rawCommand(String name) {
        byte[] raw = name.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return () -> raw;
    }

    private static String stringifyRedisResult(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        if (o instanceof List<?> list) {
            List<String> bits = new ArrayList<>();
            for (Object x : list) {
                bits.add(stringifyRedisResult(x));
            }
            return bits.toString();
        }
        return String.valueOf(o);
    }

    private static JedisPool createPool(DatabaseConfig config) {
        int timeoutMs = Math.max(1_000, config.connectionTimeoutSeconds() * 1000);
        HostAndPort hp = new HostAndPort(config.host(), config.port());
        DefaultJedisClientConfig.Builder b =
                DefaultJedisClientConfig.builder()
                        .connectionTimeoutMillis(timeoutMs)
                        .socketTimeoutMillis(timeoutMs);
        String pass = config.password();
        if (pass != null && !pass.isBlank()) {
            b.password(pass);
        }
        if (config.username() != null && !config.username().isBlank()) {
            b.user(config.username());
        }
        return new JedisPool(hp, b.build());
    }

    private static QueryExecutionResult success(
            long startMs, List<Map<String, Object>> rows, int affectedRows) {
        long elapsed = System.currentTimeMillis() - startMs;
        QueryExecutionResult r = new QueryExecutionResult();
        r.setSuccess(true);
        r.setRows(rows);
        r.setAffectedRows(affectedRows);
        r.setErrorMessage(null);
        r.setExecutionTimeMs(elapsed);
        return r;
    }

    private static QueryExecutionResult failure(long startMs, String message) {
        long elapsed = System.currentTimeMillis() - startMs;
        QueryExecutionResult r = new QueryExecutionResult();
        r.setSuccess(false);
        r.setRows(List.of());
        r.setAffectedRows(0);
        r.setErrorMessage(message);
        r.setExecutionTimeMs(elapsed);
        return r;
    }
}
