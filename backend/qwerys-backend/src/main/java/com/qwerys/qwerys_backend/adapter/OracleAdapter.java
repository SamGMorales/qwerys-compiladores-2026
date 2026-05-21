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
import java.util.regex.Pattern;

/**
 * Oracle Database via JDBC thin driver ({@code ojdbc11}).
 */
public final class OracleAdapter implements DatabaseAdapter {

    private static final String DB_TYPE = "oracle";
    private static final Pattern LIMIT_TOKEN = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public String getDbType() {
        return DB_TYPE;
    }

    @Override
    public boolean testConnection(DatabaseConfig config) {
        try (Connection ignored = openConnectionUnchecked(config)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> getTableNames(DatabaseConfig config) {
        String sql = "SELECT TABLE_NAME FROM USER_TABLES ORDER BY TABLE_NAME";
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
        String sql =
                "SELECT COLUMN_NAME FROM USER_TAB_COLUMNS WHERE TABLE_NAME = UPPER(?) ORDER BY COLUMN_ID";
        try (Connection conn = openConnection(config);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> cols = new ArrayList<>();
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
                return cols;
            }
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    @Override
    public Map<String, String> getColumnTypes(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        String sql =
                "SELECT COLUMN_NAME, DATA_TYPE FROM USER_TAB_COLUMNS "
                        + "WHERE TABLE_NAME = UPPER(?) ORDER BY COLUMN_ID";
        try (Connection conn = openConnection(config);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, String> types = new LinkedHashMap<>();
                while (rs.next()) {
                    types.put(rs.getString(1), rs.getString(2));
                }
                return types;
            }
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    @Override
    public boolean tableExists(DatabaseConfig config, String tableName) {
        requireTableName(tableName);
        String sql = "SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME = UPPER(?)";
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
        if (LIMIT_TOKEN.matcher(sql).find()) {
            return failure(start, "Oracle uses ROWNUM or FETCH FIRST instead of LIMIT");
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
            List<String> colNames = getColumnNames(config, table);
            Map<String, String> colTypes = getColumnTypes(config, table);
            Map<String, ColumnMeta> meta = readColumnMeta(config, table);
            List<String> pkOrdered = readPrimaryKeyColumns(config, table);
            Set<String> pkSet = new LinkedHashSet<>(pkOrdered);

            List<ColumnSchema> columns = new ArrayList<>();
            for (String col : colNames) {
                ColumnMeta m = meta.get(col);
                ColumnSchema cs = new ColumnSchema();
                cs.setColumnName(col);
                cs.setDataType(colTypes.getOrDefault(col, ""));
                cs.setNullable(m == null || m.nullable);
                cs.setPrimaryKey(pkSet.contains(col));
                cs.setDefaultValue(m != null ? m.defaultValue : null);
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

    private record ColumnMeta(boolean nullable, String defaultValue) {}

    private static Map<String, ColumnMeta> readColumnMeta(DatabaseConfig config, String tableName) {
        String sql =
                "SELECT COLUMN_NAME, NULLABLE, DATA_DEFAULT FROM USER_TAB_COLUMNS "
                        + "WHERE TABLE_NAME = UPPER(?) ORDER BY COLUMN_ID";
        try (Connection conn = openConnection(config);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, ColumnMeta> map = new LinkedHashMap<>();
                while (rs.next()) {
                    String col = rs.getString(1);
                    boolean nullable = "Y".equalsIgnoreCase(rs.getString(2));
                    map.put(col, new ColumnMeta(nullable, rs.getString(3)));
                }
                return map;
            }
        } catch (SQLException e) {
            throw connectionFailed(e);
        }
    }

    private static List<String> readPrimaryKeyColumns(DatabaseConfig config, String tableName) {
        String sql =
                "SELECT ucc.COLUMN_NAME, ucc.POSITION "
                        + "FROM USER_CONSTRAINTS uc "
                        + "JOIN USER_CONS_COLUMNS ucc ON uc.CONSTRAINT_NAME = ucc.CONSTRAINT_NAME "
                        + "WHERE uc.CONSTRAINT_TYPE = 'P' AND uc.TABLE_NAME = UPPER(?) "
                        + "ORDER BY ucc.POSITION";
        try (Connection conn = openConnection(config);
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                TreeMap<Integer, String> ordered = new TreeMap<>();
                while (rs.next()) {
                    String col = rs.getString(1);
                    if (col != null) {
                        ordered.put(rs.getInt(2), col);
                    }
                }
                return new ArrayList<>(ordered.values());
            }
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
                || head.startsWith("SHOW")
                || head.startsWith("TABLE")
                || head.startsWith("VALUES")
                || head.startsWith("DESCRIBE")
                || head.startsWith("DESC")
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
        return DriverManager.getConnection(
                jdbcUrl(config),
                config.username() != null ? config.username() : "",
                config.password() != null ? config.password() : "");
    }

    private static Connection openConnectionUnchecked(DatabaseConfig config) throws SQLException {
        return openConnection(config);
    }

    private static String jdbcUrl(DatabaseConfig config) {
        String db = config.database();
        if (db == null || db.isBlank()) {
            throw new IllegalStateException("Oracle JDBC URL requires a service name or SID in DatabaseConfig.database");
        }
        return String.format(
                Locale.ROOT,
                "jdbc:oracle:thin:@//%s:%d/%s",
                config.host(),
                config.port(),
                db);
    }

    private static void requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
    }

    private static IllegalStateException connectionFailed(SQLException e) {
        return new IllegalStateException("Oracle connection failed: " + e.getMessage(), e);
    }
}
