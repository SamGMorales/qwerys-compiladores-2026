package com.qwerys.qwerys_backend.adapter;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Amazon DynamoDB via AWS SDK for Java v2 (PartiQL for {@link #executeQuery}).
 */
public final class DynamoDBAdapter implements DatabaseAdapter {

    private static final String DB_TYPE = "dynamodb";

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public boolean testConnection(DatabaseConfig config) {
        try (DynamoDbClient client = buildClient(config)) {
            client.listTables(r -> r.limit(1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> getTableNames(DatabaseConfig config) {
        try (DynamoDbClient client = buildClient(config)) {
            return client.listTables().tableNames();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<String> getColumnNames(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        try (DynamoDbClient client = buildClient(config)) {
            return new ArrayList<>(attributeNames(client, tableName));
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public Map<String, String> getColumnTypes(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        try (DynamoDbClient client = buildClient(config)) {
            Map<String, String> types = new LinkedHashMap<>();
            for (AttributeDefinition def :
                    client.describeTable(DescribeTableRequest.builder().tableName(tableName).build())
                            .table()
                            .attributeDefinitions()) {
                types.put(def.attributeName(), scalarType(def.attributeType()));
            }
            return types;
        } catch (Exception e) {
            return Map.of();
        }
    }

    @Override
    public boolean tableExists(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        try (DynamoDbClient client = buildClient(config)) {
            client.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
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
        String partiql = query.strip();
        if (partiql.isEmpty()) {
            return failure(start, "Empty query");
        }
        try (DynamoDbClient client = buildClient(config)) {
            ExecuteStatementResponse resp =
                    client.executeStatement(ExecuteStatementRequest.builder().statement(partiql).build());
            List<Map<String, Object>> rows = new ArrayList<>();
            if (resp.items() != null) {
                for (Map<String, AttributeValue> item : resp.items()) {
                    rows.add(flattenItem(item));
                }
            }
            int affected = resp.items() != null ? resp.items().size() : 0;
            return success(start, rows, affected);
        } catch (Exception e) {
            return failure(start, e.getMessage());
        }
    }

    @Override
    public DatabaseSchema getSchema(DatabaseConfig config) {
        List<String> tables = getTableNames(config);
        DatabaseSchema schema = new DatabaseSchema();
        schema.setDbType(DB_TYPE);
        schema.setDatabaseName(config.database() != null ? config.database() : "");
        List<TableSchema> tableSchemas = new ArrayList<>();
        try (DynamoDbClient client = buildClient(config)) {
            for (String table : tables) {
                var desc = client.describeTable(DescribeTableRequest.builder().tableName(table).build()).table();
                Set<String> pk = new LinkedHashSet<>();
                for (KeySchemaElement ks : desc.keySchema()) {
                    if (ks.keyType() == KeyType.HASH || ks.keyType() == KeyType.RANGE) {
                        pk.add(ks.attributeName());
                    }
                }
                List<ColumnSchema> columns = new ArrayList<>();
                if (desc.attributeDefinitions() != null) {
                    for (AttributeDefinition def : desc.attributeDefinitions()) {
                        ColumnSchema cs = new ColumnSchema();
                        cs.setColumnName(def.attributeName());
                        cs.setDataType(scalarType(def.attributeType()));
                        cs.setNullable(!pk.contains(def.attributeName()));
                        cs.setPrimaryKey(pk.contains(def.attributeName()));
                        cs.setDefaultValue(null);
                        columns.add(cs);
                    }
                }
                TableSchema ts = new TableSchema();
                ts.setTableName(table);
                ts.setColumns(columns);
                ts.setPrimaryKeys(new ArrayList<>(pk));
                ts.setForeignKeys(List.of());
                tableSchemas.add(ts);
            }
        } catch (Exception e) {
            schema.setTables(List.of());
            return schema;
        }
        schema.setTables(tableSchemas);
        return schema;
    }

    private static Set<String> attributeNames(DynamoDbClient client, String tableName) {
        Set<String> names = new LinkedHashSet<>();
        var desc = client.describeTable(DescribeTableRequest.builder().tableName(tableName).build()).table();
        if (desc.attributeDefinitions() != null) {
            for (AttributeDefinition def : desc.attributeDefinitions()) {
                names.add(def.attributeName());
            }
        }
        return names;
    }

    private static String scalarType(software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType t) {
        if (t == null) {
            return "unknown";
        }
        return switch (t) {
            case S -> "string";
            case N -> "number";
            case B -> "binary";
            default -> "unknown";
        };
    }

    private static Map<String, Object> flattenItem(Map<String, AttributeValue> item) {
        Map<String, Object> row = new LinkedHashMap<>();
        item.forEach((k, v) -> row.put(k, attrToJava(v)));
        return row;
    }

    private static Object attrToJava(AttributeValue v) {
        if (v == null) {
            return null;
        }
        if (Boolean.TRUE.equals(v.nul())) {
            return null;
        }
        if (v.s() != null) {
            return v.s();
        }
        if (v.n() != null) {
            return v.n();
        }
        if (v.bool() != null) {
            return v.bool();
        }
        if (v.m() != null && !v.m().isEmpty()) {
            Map<String, Object> inner = new LinkedHashMap<>();
            v.m().forEach((ik, iv) -> inner.put(ik, attrToJava(iv)));
            return inner;
        }
        if (v.l() != null && !v.l().isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (AttributeValue av : v.l()) {
                list.add(attrToJava(av));
            }
            return list;
        }
        if (v.ss() != null && !v.ss().isEmpty()) {
            return new ArrayList<>(v.ss());
        }
        if (v.ns() != null && !v.ns().isEmpty()) {
            return new ArrayList<>(v.ns());
        }
        if (v.b() != null) {
            return v.b().asByteArray();
        }
        return v.toString();
    }

    private static DynamoDbClient buildClient(DatabaseConfig config) {
        String region = config.database();
        if (region == null || region.isBlank()) {
            region = System.getenv("AWS_REGION");
        }
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
        String scheme = config.port() == 443 ? "https" : "http";
        URI endpoint = URI.create(String.format(Locale.ROOT, "%s://%s:%d", scheme, config.host(), config.port()));
        AwsCredentialsProvider creds;
        if (config.username() != null && !config.username().isBlank()) {
            creds =
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(
                                    config.username(), config.password() != null ? config.password() : ""));
        } else {
            creds = DefaultCredentialsProvider.create();
        }
        return DynamoDbClient.builder()
                .region(Region.of(region))
                .endpointOverride(endpoint)
                .credentialsProvider(creds)
                .build();
    }

    private static void requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
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
