package com.qwerys.qwerys_backend.adapter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * SQLite file database via JDBC ({@code sqlite-jdbc}). {@link DatabaseConfig#database()} is the path
 * to the {@code .db} file; {@code host} and {@code port} are ignored.
 */
public final class SQLiteAdapter implements DatabaseAdapter {

    private static final String DB_TYPE = "sqlite";

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public boolean testConnection(DatabaseConfig config) {
        try {
            String path = config.database();
            if (path == null || path.isBlank()) {
                return false;
            }
            if (!java.nio.file.Files.exists(java.nio.file.Path.of(path))) {
                return false;
            }
            try (Connection ignored = openConnectionUnchecked(config)) {
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> getTableNames(DatabaseConfig config) {
        String sql =
                "SELECT name FROM sqlite_master "
                        + "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
        try (Connection conn = openConnection(config);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names;
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    @Override
    public List<String> getColumnNames(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        try (Connection conn = openConnection(config);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")")) {
            List<String> cols = new ArrayList<>();
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
            return cols;
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    @Override
    public Map<String, String> getColumnTypes(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        try (Connection conn = openConnection(config);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")")) {
            Map<String, String> types = new LinkedHashMap<>();
            while (rs.next()) {
                types.put(rs.getString("name"), rs.getString("type"));
            }
            return types;
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    @Override
    public boolean tableExists(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        String sql =
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?";
        try (Connection conn = openConnection(config);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    @Override
    public QueryExecutionResult executeQuery(DatabaseConfig config, String query) {
        long start = System.currentTimeMillis();
        if (query == null) {
            return failure(start, "Query is null");
        }
        String sql = query.strip();
        if (sql.isEmpty()) {
            return failure(start, "Empty query");
        }
        try (Connection conn = openConnection(config);
                Statement stmt = conn.createStatement()) {
            if (returnsResultSet(sql)) {
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    List<Map<String, Object>> rows = readRows(rs);
                    return success(start, rows, 0);
                }
            } else {
                int affected = stmt.executeUpdate(sql);
                return success(start, List.of(), affected);
            }
        } catch (SQLException e) {
            return failure(start, e.getMessage());
        }
    }

    @Override
    public DatabaseSchema getSchema(DatabaseConfig config) {
        List<String> tables = getTableNames(config);
        DatabaseSchema schema = new DatabaseSchema();
        schema.setDbType(DB_TYPE);
        schema.setDatabaseName(config.database());
        List<TableSchema> tableSchemas = new ArrayList<>();
        for (String table : tables) {
            List<ColumnSchema> columns = readColumnsWithMeta(config, table);
            List<String> pkOrdered = readPrimaryKeyColumns(config, table);
            Set<String> pkSet = new LinkedHashSet<>(pkOrdered);
            for (ColumnSchema cs : columns) {
                cs.setPrimaryKey(pkSet.contains(cs.getColumnName()));
            }
            TableSchema ts = new TableSchema();
            ts.setTableName(table);
            ts.setColumns(columns);
            ts.setPrimaryKeys(pkOrdered);
            ts.setForeignKeys(readForeignKeys(config, table));
            tableSchemas.add(ts);
        }
        schema.setTables(tableSchemas);
        return schema;
    }

    private static List<ColumnSchema> readColumnsWithMeta(DatabaseConfig config, String tableName) {
        try (Connection conn = openConnection(config);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")")) {
            List<ColumnSchema> list = new ArrayList<>();
            while (rs.next()) {
                ColumnSchema cs = new ColumnSchema();
                cs.setColumnName(rs.getString("name"));
                cs.setDataType(rs.getString("type"));
                cs.setNullable(rs.getInt("notnull") == 0);
                cs.setPrimaryKey(rs.getInt("pk") != 0);
                cs.setDefaultValue(rs.getString("dflt_value"));
                list.add(cs);
            }
            return list;
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    private static List<String> readPrimaryKeyColumns(DatabaseConfig config, String tableName) {
        try (Connection conn = openConnection(config);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + quoteIdentifier(tableName) + ")")) {
            TreeMap<Integer, String> ordered = new TreeMap<>();
            while (rs.next()) {
                if (rs.getInt("pk") > 0) {
                    ordered.put(rs.getInt("pk"), rs.getString("name"));
                }
            }
            return new ArrayList<>(ordered.values());
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    private static List<String> readForeignKeys(DatabaseConfig config, String tableName) {
        try (Connection conn = openConnection(config);
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA foreign_key_list(" + quoteIdentifier(tableName) + ")")) {
            List<String> fks = new ArrayList<>();
            while (rs.next()) {
                String from = rs.getString("from");
                String toTable = rs.getString("table");
                String to = rs.getString("to");
                fks.add(from + " -> " + toTable + "." + to);
            }
            return fks;
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    private static List<Map<String, Object>> readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> labels = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            String label = md.getColumnLabel(i);
            if (label == null || label.isBlank()) {
                label = md.getColumnName(i);
            }
            labels.add(label);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= n; i++) {
                row.put(labels.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
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

    private static boolean returnsResultSet(String sql) {
        String head = leadingTokenUpper(sql);
        return head.startsWith("SELECT")
                || head.startsWith("WITH")
                || head.startsWith("PRAGMA")
                || head.startsWith("VALUES")
                || head.startsWith("EXPLAIN");
    }

    private static String leadingTokenUpper(String sql) {
        int i = 0;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return "";
                }
                i = end + 2;
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i + 2);
                if (nl < 0) {
                    return "";
                }
                i = nl + 1;
                continue;
            }
            break;
        }
        int start = i;
        while (i < len && !Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (start >= len) {
            return "";
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    private static Connection openConnection(DatabaseConfig config) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(config));
    }

    private static Connection openConnectionUnchecked(DatabaseConfig config) throws SQLException {
        return openConnection(config);
    }

    private static String jdbcUrl(DatabaseConfig config) {
        String path = config.database();
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("SQLite database path is required (DatabaseConfig.database)");
        }
        return "jdbc:sqlite:" + path;
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static void requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
    }

    private static IllegalStateException connectionFailed(SQLException e) {
        return new IllegalStateException("SQLite connection failed: " + e.getMessage(), e);
    }
}
