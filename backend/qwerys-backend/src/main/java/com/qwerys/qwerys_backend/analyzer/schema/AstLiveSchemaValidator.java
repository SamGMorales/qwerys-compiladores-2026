package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.adapter.ColumnSchema;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates SQL/PartiQL AST nodes (TABLE_REF, COLUMN_REF, COMPARISON) against a live {@link DatabaseSchema}.
 * Used by {@link com.qwerys.qwerys_backend.analyzer.SchemaAwareSemanticAnalyzer} and DynamoDB PartiQL.
 */
public final class AstLiveSchemaValidator {

    private AstLiveSchemaValidator() {
    }

    /**
     * Appends SCH-001/002/003 findings to {@code findings} when entities or columns are missing or mismatched.
     */
    public static void validate(
            AstNode root,
            DatabaseSchema schema,
            Locale ui,
            SchemaEntityLabels labels,
            List<SemanticError> findings) {
        if (root == null || schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            return;
        }
        Map<String, TableSchema> tableIndex = SchemaValidationSupport.buildTableIndex(schema);
        Set<String> reportedTables = new HashSet<>();
        Set<String> reportedColumns = new HashSet<>();

        String rootType = root.getNodeType();
        if ("WITH_SELECT_STATEMENT".equals(rootType)) {
            for (AstNode c : root.getChildren()) {
                if ("SELECT_STATEMENT".equals(c.getNodeType())) {
                    validateSelectLike(c, tableIndex, reportedTables, reportedColumns, findings, ui, labels);
                }
            }
            return;
        }

        switch (rootType) {
            case "SELECT_STATEMENT" ->
                    validateSelectLike(root, tableIndex, reportedTables, reportedColumns, findings, ui, labels);
            case "DELETE_STATEMENT", "UPDATE_STATEMENT", "INSERT_STATEMENT" ->
                    validateDmlStatement(root, tableIndex, reportedTables, reportedColumns, findings, ui, labels);
            default -> { }
        }
    }

    private static void validateSelectLike(
            AstNode stmt,
            Map<String, TableSchema> tableIndex,
            Set<String> reportedTables,
            Set<String> reportedColumns,
            List<SemanticError> findings,
            Locale ui,
            SchemaEntityLabels labels) {
        Map<String, String> aliasToTable = new HashMap<>();
        Set<String> tablesInQuery = new LinkedHashSet<>();
        collectTableBindings(stmt, aliasToTable, tablesInQuery);

        for (String table : tablesInQuery) {
            SchemaValidationSupport.reportMissingEntity(table, tableIndex, reportedTables, findings, ui, labels);
        }

        AstNode columnList = findChild(stmt, "COLUMN_LIST");
        if (columnList != null) {
            validateColumnRefs(columnList, aliasToTable, tablesInQuery, tableIndex, reportedColumns, findings, ui, labels);
        }

        AstNode whereClause = findChild(stmt, "WHERE_CLAUSE");
        if (whereClause != null) {
            validateColumnRefs(whereClause, aliasToTable, tablesInQuery, tableIndex, reportedColumns, findings, ui, labels);
            checkTypeMismatches(whereClause, aliasToTable, tablesInQuery, tableIndex, findings, ui);
        }

        AstNode orderBy = findChild(stmt, "ORDER_BY");
        if (orderBy != null) {
            validateColumnRefs(orderBy, aliasToTable, tablesInQuery, tableIndex, reportedColumns, findings, ui, labels);
        }
    }

    private static void validateDmlStatement(
            AstNode stmt,
            Map<String, TableSchema> tableIndex,
            Set<String> reportedTables,
            Set<String> reportedColumns,
            List<SemanticError> findings,
            Locale ui,
            SchemaEntityLabels labels) {
        Map<String, String> aliasToTable = new HashMap<>();
        Set<String> tablesInQuery = new LinkedHashSet<>();
        collectTableBindings(stmt, aliasToTable, tablesInQuery);

        for (String table : tablesInQuery) {
            SchemaValidationSupport.reportMissingEntity(table, tableIndex, reportedTables, findings, ui, labels);
        }

        String primaryTable = tablesInQuery.size() == 1 ? tablesInQuery.iterator().next() : null;

        AstNode insertCols = findChild(stmt, "COLUMN_LIST");
        if (insertCols != null && primaryTable != null) {
            for (AstNode col : insertCols.getChildren()) {
                if ("COLUMN_REF".equals(col.getNodeType()) && col.getValue() != null && !"*".equals(col.getValue())) {
                    SchemaValidationSupport.reportMissingField(
                            col.getValue(), primaryTable, tableIndex, reportedColumns, findings, ui, labels);
                }
            }
        }

        AstNode setClause = findChild(stmt, "SET_CLAUSE");
        if (setClause != null && primaryTable != null) {
            for (AstNode assign : setClause.getChildren()) {
                if ("ASSIGNMENT".equals(assign.getNodeType()) && assign.getValue() != null) {
                    SchemaValidationSupport.reportMissingField(
                            assign.getValue(), primaryTable, tableIndex, reportedColumns, findings, ui, labels);
                }
            }
        }

        AstNode whereClause = findChild(stmt, "WHERE_CLAUSE");
        if (whereClause != null) {
            validateColumnRefs(whereClause, aliasToTable, tablesInQuery, tableIndex, reportedColumns, findings, ui, labels);
            checkTypeMismatches(whereClause, aliasToTable, tablesInQuery, tableIndex, findings, ui);
        }
    }

    private static void collectTableBindings(AstNode stmt, Map<String, String> aliasToTable, Set<String> tablesInQuery) {
        if (stmt == null) {
            return;
        }
        for (AstNode ref : findAllChildrenDeep(stmt, "TABLE_REF")) {
            registerTableRef(ref, aliasToTable, tablesInQuery);
        }
    }

    private static void registerTableRef(AstNode ref, Map<String, String> aliasToTable, Set<String> tablesInQuery) {
        if (ref.getValue() == null || ref.getValue().isBlank()) {
            return;
        }
        String bare = SchemaValidationSupport.bareEntityName(ref.getValue());
        tablesInQuery.add(bare);
        aliasToTable.put(SchemaValidationSupport.normalizeKey(bare), bare);
        AstNode aliasNode = findChild(ref, "ALIAS");
        if (aliasNode != null && aliasNode.getValue() != null) {
            aliasToTable.put(SchemaValidationSupport.normalizeKey(aliasNode.getValue()), bare);
        }
    }

    private static void validateColumnRefs(
            AstNode scope,
            Map<String, String> aliasToTable,
            Set<String> tablesInQuery,
            Map<String, TableSchema> tableIndex,
            Set<String> reportedColumns,
            List<SemanticError> findings,
            Locale ui,
            SchemaEntityLabels labels) {
        for (AstNode col : findAllChildrenDeep(scope, "COLUMN_REF")) {
            String value = col.getValue();
            if (value == null || value.isBlank() || "*".equals(value)) {
                continue;
            }
            String table = resolveTableForColumn(value, aliasToTable, tablesInQuery);
            if (table == null) {
                continue;
            }
            if (!SchemaValidationSupport.tableExists(table, tableIndex)) {
                continue;
            }
            SchemaValidationSupport.reportMissingField(value, table, tableIndex, reportedColumns, findings, ui, labels);
        }
    }

    private static void checkTypeMismatches(
            AstNode whereClause,
            Map<String, String> aliasToTable,
            Set<String> tablesInQuery,
            Map<String, TableSchema> tableIndex,
            List<SemanticError> findings,
            Locale ui) {
        Set<String> reported = new HashSet<>();
        for (AstNode cmp : findAllChildrenDeep(whereClause, "COMPARISON")) {
            if (cmp.getChildren().size() < 2) {
                continue;
            }
            AstNode left = cmp.getChildren().get(0);
            AstNode right = cmp.getChildren().get(1);
            AstNode colNode = null;
            AstNode litNode = null;
            if ("COLUMN_REF".equals(left.getNodeType()) && "LITERAL".equals(right.getNodeType())) {
                colNode = left;
                litNode = right;
            } else if ("COLUMN_REF".equals(right.getNodeType()) && "LITERAL".equals(left.getNodeType())) {
                colNode = right;
                litNode = left;
            }
            if (colNode == null || litNode == null || colNode.getValue() == null) {
                continue;
            }
            String table = resolveTableForColumn(colNode.getValue(), aliasToTable, tablesInQuery);
            if (table == null || !SchemaValidationSupport.tableExists(table, tableIndex)) {
                continue;
            }
            TableSchema ts = tableIndex.get(SchemaValidationSupport.normalizeKey(
                    SchemaValidationSupport.bareEntityName(table)));
            ColumnSchema cs = SchemaValidationSupport.findColumn(ts, SchemaValidationSupport.fieldPart(colNode.getValue()));
            if (cs == null || cs.getDataType() == null) {
                continue;
            }
            SchemaValidationSupport.TypeFamily colFamily =
                    SchemaValidationSupport.typeFamilyFromSqlType(cs.getDataType());
            SchemaValidationSupport.TypeFamily litFamily =
                    SchemaValidationSupport.typeFamilyFromLiteral(litNode.getValue());
            if (colFamily == SchemaValidationSupport.TypeFamily.UNKNOWN
                    || litFamily == SchemaValidationSupport.TypeFamily.UNKNOWN) {
                continue;
            }
            if (colFamily == litFamily) {
                continue;
            }
            SchemaValidationSupport.reportTypeMismatch(colNode.getValue(), colFamily, litFamily, reported, findings, ui);
        }
    }

    private static String resolveTableForColumn(
            String colRef, Map<String, String> aliasToTable, Set<String> tablesInQuery) {
        int dot = colRef.indexOf('.');
        if (dot >= 0) {
            String prefix = colRef.substring(0, dot);
            String mapped = aliasToTable.get(SchemaValidationSupport.normalizeKey(prefix));
            if (mapped != null) {
                return mapped;
            }
            for (String t : tablesInQuery) {
                if (SchemaValidationSupport.normalizeKey(t).equals(SchemaValidationSupport.normalizeKey(prefix))
                        || SchemaValidationSupport.normalizeKey(SchemaValidationSupport.bareEntityName(t))
                                .equals(SchemaValidationSupport.normalizeKey(prefix))) {
                    return t;
                }
            }
            return SchemaValidationSupport.bareEntityName(prefix);
        }
        if (tablesInQuery.size() == 1) {
            return tablesInQuery.iterator().next();
        }
        return null;
    }

    private static AstNode findChild(AstNode parent, String nodeType) {
        for (AstNode child : parent.getChildren()) {
            if (nodeType.equals(child.getNodeType())) {
                return child;
            }
        }
        return null;
    }

    private static List<AstNode> findAllChildrenDeep(AstNode node, String nodeType) {
        List<AstNode> result = new ArrayList<>();
        findAllDeepHelper(node, nodeType, result);
        return result;
    }

    private static void findAllDeepHelper(AstNode node, String nodeType, List<AstNode> out) {
        if (nodeType.equals(node.getNodeType())) {
            out.add(node);
        }
        for (AstNode child : node.getChildren()) {
            findAllDeepHelper(child, nodeType, out);
        }
    }
}
