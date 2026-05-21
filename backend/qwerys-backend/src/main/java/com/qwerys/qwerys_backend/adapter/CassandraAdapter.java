package com.qwerys.qwerys_backend.adapter;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apache Cassandra access via the DataStax Java driver.
 */
public final class CassandraAdapter implements DatabaseAdapter {

    private static final String DB_TYPE = "cassandra";
    /** Driver 4+ requires a local datacenter name; override with {@code -Dqwerys.cassandra.local-dc=...}. */
    private static final String DEFAULT_LOCAL_DC = "datacenter1";

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public boolean testConnection(DatabaseConfig config) {
        try (CqlSession session = buildSession(config)) {
            Row row = session.execute("SELECT release_version FROM system.local").one();
            return row != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> getTableNames(DatabaseConfig config) {
        String ks = keyspaceName(config);
        if (ks == null) {
            return List.of();
        }
        try (CqlSession session = buildSession(config)) {
            ResultSet rs =
                    session.execute(
                            SimpleStatement.newInstance(
                                    "SELECT table_name FROM system_schema.tables WHERE keyspace_name = ?",
                                    ks));
            List<String> names = new ArrayList<>();
            for (Row row : rs) {
                names.add(row.getString("table_name"));
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public List<String> getColumnNames(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        String ks = keyspaceName(config);
        if (ks == null) {
            return List.of();
        }
        try (CqlSession session = buildSession(config)) {
            ResultSet rs =
                    session.execute(
                            SimpleStatement.newInstance(
                                    "SELECT column_name FROM system_schema.columns "
                                            + "WHERE keyspace_name = ? AND table_name = ? ORDER BY position",
                                    ks,
                                    tableName));
            List<String> cols = new ArrayList<>();
            for (Row row : rs) {
                cols.add(row.getString("column_name"));
            }
            return cols;
        } catch (Exception e) {
            throw new IllegalStateException("Cassandra connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, String> getColumnTypes(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        String ks = keyspaceName(config);
        if (ks == null) {
            return Map.of();
        }
        try (CqlSession session = buildSession(config)) {
            ResultSet rs =
                    session.execute(
                            SimpleStatement.newInstance(
                                    "SELECT column_name, type FROM system_schema.columns "
                                            + "WHERE keyspace_name = ? AND table_name = ? ORDER BY position",
                                    ks,
                                    tableName));
            Map<String, String> types = new LinkedHashMap<>();
            for (Row row : rs) {
                types.put(row.getString("column_name"), row.getString("type"));
            }
            return types;
        } catch (Exception e) {
            throw new IllegalStateException("Cassandra connection failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean tableExists(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        String ks = keyspaceName(config);
        if (ks == null) {
            return false;
        }
        try (CqlSession session = buildSession(config)) {
            Row row =
                    session.execute(
                                    SimpleStatement.newInstance(
                                            "SELECT table_name FROM system_schema.tables "
                                                    + "WHERE keyspace_name = ? AND table_name = ?",
                                            ks,
                                            tableName))
                            .one();
            return row != null;
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
        String cql = query.strip();
        if (cql.isEmpty()) {
            return failure(start, "Empty query");
        }
        try (CqlSession session = buildSession(config)) {
            ResultSet rs = session.execute(SimpleStatement.newInstance(cql));
            List<Map<String, Object>> rows = new ArrayList<>();
            int n = rs.getColumnDefinitions().size();
            for (Row row : rs) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    String col = rs.getColumnDefinitions().get(i).getName().asInternal();
                    m.put(col, row.getObject(i));
                }
                rows.add(m);
            }
            return success(start, rows, 0);
        } catch (Exception e) {
            return failure(start, e.getMessage());
        }
    }

    @Override
    public DatabaseSchema getSchema(DatabaseConfig config) {
        List<String> tables = getTableNames(config);
        DatabaseSchema schema = new DatabaseSchema();
        schema.setDbType(DB_TYPE);
        schema.setDatabaseName(keyspaceName(config) != null ? keyspaceName(config) : "");
        List<TableSchema> tableSchemas = new ArrayList<>();
        for (String table : tables) {
            List<String> colNames = getColumnNames(config, table);
            Map<String, String> colTypes = getColumnTypes(config, table);
            List<String> pkOrdered = readPrimaryKeyColumns(config, table);
            Set<String> pkSet = new LinkedHashSet<>(pkOrdered);

            List<ColumnSchema> columns = new ArrayList<>();
            for (String col : colNames) {
                String dataType = colTypes.getOrDefault(col, "");
                ColumnSchema cs = new ColumnSchema();
                cs.setColumnName(col);
                cs.setDataType(dataType);
                cs.setNullable(true);
                cs.setPrimaryKey(pkSet.contains(col));
                cs.setDefaultValue(null);
                columns.add(cs);
            }
            TableSchema ts = new TableSchema();
            ts.setTableName(table);
            ts.setColumns(columns);
            ts.setPrimaryKeys(pkOrdered);
            ts.setForeignKeys(List.of());
            tableSchemas.add(ts);
        }
        schema.setTables(tableSchemas);
        return schema;
    }

    private static List<String> readPrimaryKeyColumns(DatabaseConfig config, String tableName) {
        String ks = keyspaceName(config);
        if (ks == null) {
            return List.of();
        }
        try (CqlSession session = buildSession(config)) {
            ResultSet rs =
                    session.execute(
                            SimpleStatement.newInstance(
                                    "SELECT column_name FROM system_schema.columns "
                                            + "WHERE keyspace_name = ? AND table_name = ? "
                                            + "AND kind IN ('partition_key','clustering') ORDER BY position",
                                    ks,
                                    tableName));
            List<String> pk = new ArrayList<>();
            for (Row row : rs) {
                pk.add(row.getString("column_name"));
            }
            return pk;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static CqlSession buildSession(DatabaseConfig config) {
        CqlSessionBuilder b =
                CqlSession.builder()
                        .addContactPoint(new InetSocketAddress(config.host(), config.port()))
                        .withLocalDatacenter(localDatacenter());
        String user = config.username();
        if (user != null && !user.isBlank()) {
            b.withAuthCredentials(user, config.password() != null ? config.password() : "");
        }
        return b.build();
    }

    private static String localDatacenter() {
        String p = System.getProperty("qwerys.cassandra.local-dc");
        if (p != null && !p.isBlank()) {
            return p.strip();
        }
        return DEFAULT_LOCAL_DC;
    }

    private static String keyspaceName(DatabaseConfig config) {
        String db = config.database();
        if (db == null || db.isBlank()) {
            return null;
        }
        return db;
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
