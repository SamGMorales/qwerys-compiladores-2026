package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.adapter.ColumnSchema;
import com.qwerys.qwerys_backend.adapter.DatabaseAdapter;
import com.qwerys.qwerys_backend.adapter.DatabaseAdapterFactory;
import com.qwerys.qwerys_backend.adapter.DatabaseConfig;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.AstNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves live-schema column names for optimization hints (OPT-001, OPT-003).
 */
public final class SchemaOptimizationSupport {

    private static final Pattern SELECT_STAR =
            Pattern.compile("(?is)(\\bSELECT\\s+(?:DISTINCT\\s+|ALL\\s+)?)\\*(?=\\s)");

    private SchemaOptimizationSupport() {
    }

    /** Placeholder when no schema is available. */
    public static String placeholderColumnList() {
        return "col1, col2, col3";
    }

    public static String formatColumnList(List<String> schemaColumns) {
        if (schemaColumns == null || schemaColumns.isEmpty()) {
            return placeholderColumnList();
        }
        return String.join(", ", schemaColumns);
    }

    /**
     * Replaces the first {@code SELECT *} (or {@code SELECT DISTINCT *}) with explicit column names.
     */
    public static String expandSelectStar(String sql, List<String> schemaColumns) {
        if (sql == null || schemaColumns == null || schemaColumns.isEmpty()) {
            return sql;
        }
        String cols = formatColumnList(schemaColumns);
        Matcher m = SELECT_STAR.matcher(sql);
        if (m.find()) {
            return m.replaceFirst(m.group(1) + cols);
        }
        return sql;
    }

    /**
     * Column names of the primary {@code TABLE_REF} in the query AST, loaded from a live connection.
     * Returns an empty list when the table cannot be resolved or schema load fails.
     */
    public static List<String> resolvePrimaryTableColumnNames(AstNode ast, DatabaseConfig connection) {
        if (ast == null || connection == null || connection.dbType() == null || connection.dbType().isBlank()) {
            return List.of();
        }
        String tableName = primaryTableName(ast);
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }
        DatabaseSchema schema = loadSchema(connection);
        if (schema == null) {
            return List.of();
        }
        TableSchema table = lookupTable(schema, tableName);
        if (table == null || table.getColumns() == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (ColumnSchema col : table.getColumns()) {
            if (col != null && col.getColumnName() != null && !col.getColumnName().isBlank()) {
                names.add(col.getColumnName());
            }
        }
        return List.copyOf(names);
    }

    private static String primaryTableName(AstNode ast) {
        AstNode stmt = ast;
        if ("WITH_SELECT_STATEMENT".equals(ast.getNodeType())) {
            for (AstNode child : ast.getChildren()) {
                if ("SELECT_STATEMENT".equals(child.getNodeType())) {
                    stmt = child;
                    break;
                }
            }
        }
        List<AstNode> refs = AstUtils.findNodes(stmt, "TABLE_REF");
        if (refs.isEmpty()) {
            return null;
        }
        return bareTableName(refs.get(0).getValue());
    }

    private static DatabaseSchema loadSchema(DatabaseConfig connection) {
        try {
            DatabaseAdapter adapter = DatabaseAdapterFactory.getAdapter(connection.dbType());
            return adapter.getSchema(connection);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static TableSchema lookupTable(DatabaseSchema schema, String tableName) {
        Map<String, TableSchema> index = new HashMap<>();
        for (TableSchema table : schema.getTables()) {
            if (table == null || table.getTableName() == null) {
                continue;
            }
            String name = table.getTableName();
            index.put(normalizeKey(name), table);
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                index.put(normalizeKey(name.substring(dot + 1)), table);
            }
        }
        return index.get(normalizeKey(bareTableName(tableName)));
    }

    private static String bareTableName(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.strip();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        int dot = t.lastIndexOf('.');
        if (dot >= 0 && dot < t.length() - 1) {
            t = t.substring(dot + 1);
        }
        return t;
    }

    private static String normalizeKey(String name) {
        return Objects.toString(name, "").trim().toLowerCase(Locale.ROOT);
    }
}
