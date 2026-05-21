package com.qwerys.qwerys_backend.analyzer.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.DynamoDbExpressionPayload;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live-schema validation for DynamoDB SDK payloads (Transact JSON, management JSON, expressions).
 */
public final class DynamoDbLiveSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern ATTR_PLACEHOLDER = Pattern.compile("#([A-Za-z0-9_]+)");
    private static final Pattern BARE_ATTR = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*(=|<|>|<=|>=|<>|BETWEEN|IN\\b)");

    private DynamoDbLiveSchemaValidator() {
    }

    public static void validateTransactJson(String raw, DatabaseSchema schema, Locale ui, List<SemanticError> findings) {
        if (raw == null || raw.isBlank() || schema == null) {
            return;
        }
        Map<String, TableSchema> tableIndex = SchemaValidationSupport.buildTableIndex(schema);
        if (tableIndex.isEmpty()) {
            return;
        }
        SchemaEntityLabels labels = SchemaEntityLabels.DYNAMODB;
        Set<String> reportedEntities = new HashSet<>();
        Set<String> reportedAttrs = new HashSet<>();
        try {
            JsonNode root = MAPPER.readTree(raw);
            JsonNode items = root.get("TransactItems");
            if (items == null || !items.isArray()) {
                return;
            }
            for (JsonNode item : items) {
                walkTransactItem(item, tableIndex, reportedEntities, reportedAttrs, findings, ui, labels);
            }
        } catch (Exception ignored) {
            // Invalid JSON handled by DynamoDbAnalyzer
        }
    }

    public static void validateManagementJson(String raw, DatabaseSchema schema, Locale ui, List<SemanticError> findings) {
        if (raw == null || raw.isBlank() || schema == null) {
            return;
        }
        Map<String, TableSchema> tableIndex = SchemaValidationSupport.buildTableIndex(schema);
        if (tableIndex.isEmpty()) {
            return;
        }
        SchemaEntityLabels labels = SchemaEntityLabels.DYNAMODB;
        Set<String> reportedEntities = new HashSet<>();
        try {
            JsonNode root = MAPPER.readTree(raw);
            collectJsonTableNames(root, reportedEntities);
            for (String table : reportedEntities) {
                SchemaValidationSupport.reportMissingEntity(table, tableIndex, new HashSet<>(), findings, ui, labels);
            }
        } catch (Exception ignored) {
        }
    }

    public static void validateExpressionPayload(String raw, DatabaseSchema schema, Locale ui, List<SemanticError> findings) {
        if (raw == null || raw.isBlank() || schema == null) {
            return;
        }
        Map<String, TableSchema> tableIndex = SchemaValidationSupport.buildTableIndex(schema);
        if (tableIndex.isEmpty()) {
            return;
        }
        SchemaEntityLabels labels = SchemaEntityLabels.DYNAMODB;
        Set<String> reportedEntities = new HashSet<>();
        Set<String> reportedAttrs = new HashSet<>();
        String tableName = null;
        Map<String, String> nameMap = Map.of();
        String expression = raw.strip();
        try {
            if (expression.startsWith("{")) {
                JsonNode root = MAPPER.readTree(expression);
                tableName = text(root, "tableName", "TableName", "table");
                nameMap = readStringMap(root.get("expressionAttributeNames"));
                if (nameMap.isEmpty()) {
                    nameMap = readStringMap(root.get("names"));
                }
            }
        } catch (Exception ignored) {
            return;
        }
        try {
            DynamoDbExpressionPayload payload = DynamoDbExpressionPayload.parse(raw);
            expression = payload.expression();
            if (nameMap.isEmpty()) {
                nameMap = payload.expressionAttributeNames();
            }
        } catch (IllegalArgumentException ignored) {
            // parse errors elsewhere
        }
        if (tableName != null && !tableName.isBlank()) {
            SchemaValidationSupport.reportMissingEntity(tableName, tableIndex, reportedEntities, findings, ui, labels);
            if (SchemaValidationSupport.tableExists(tableName, tableIndex)) {
                validateExpressionAttributes(
                        expression, nameMap, payloadAttributeTypes(raw), tableName, tableIndex,
                        reportedAttrs, new HashSet<>(), findings, ui, labels);
            }
        }
    }

    private static Map<String, String> payloadAttributeTypes(String raw) {
        try {
            if (raw != null && raw.strip().startsWith("{")) {
                JsonNode root = MAPPER.readTree(raw);
                return readTypeMap(root.get("attributeTypes"));
            }
        } catch (Exception ignored) {
        }
        return Map.of();
    }

    private static void walkTransactItem(
            JsonNode item,
            Map<String, TableSchema> tableIndex,
            Set<String> reportedEntities,
            Set<String> reportedAttrs,
            List<SemanticError> findings,
            Locale ui,
            SchemaEntityLabels labels) {
        if (item == null || !item.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> ops = item.fields();
        while (ops.hasNext()) {
            Map.Entry<String, JsonNode> op = ops.next();
            JsonNode body = op.getValue();
            String table = text(body, "TableName");
            if (table != null) {
                SchemaValidationSupport.reportMissingEntity(table, tableIndex, reportedEntities, findings, ui, labels);
                if (SchemaValidationSupport.tableExists(table, tableIndex)) {
                    JsonNode key = body.get("Key");
                    if (key != null && key.isObject()) {
                        for (Iterator<String> it = key.fieldNames(); it.hasNext(); ) {
                            String attr = it.next();
                            SchemaValidationSupport.reportMissingField(
                                    attr, table, tableIndex, reportedAttrs, findings, ui, labels);
                        }
                    }
                    String cond = text(body, "ConditionExpression", "conditionExpression");
                    if (cond != null) {
                        validateExpressionAttributes(
                                cond, Map.of(), Map.of(), table, tableIndex,
                                reportedAttrs, new HashSet<>(), findings, ui, labels);
                    }
                    String update = text(body, "UpdateExpression", "updateExpression");
                    if (update != null) {
                        validateExpressionAttributes(
                                update, Map.of(), Map.of(), table, tableIndex,
                                reportedAttrs, new HashSet<>(), findings, ui, labels);
                    }
                }
            }
        }
    }

    private static void validateExpressionAttributes(
            String expression,
            Map<String, String> nameMap,
            Map<String, String> declaredTypes,
            String table,
            Map<String, TableSchema> tableIndex,
            Set<String> reportedAttrs,
            Set<String> reportedTypes,
            List<SemanticError> findings,
            Locale ui,
            SchemaEntityLabels labels) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        Set<String> attrs = new LinkedHashSet<>();
        Matcher ph = ATTR_PLACEHOLDER.matcher(expression);
        while (ph.find()) {
            String placeholder = ph.group(1);
            String resolved = nameMap.getOrDefault("#" + placeholder, placeholder);
            attrs.add(resolved);
        }
        Matcher bare = BARE_ATTR.matcher(expression);
        while (bare.find()) {
            String id = bare.group(1);
            if (!isDynamoKeyword(id)) {
                attrs.add(id);
            }
        }
        TableSchema ts = tableIndex.get(SchemaValidationSupport.normalizeKey(
                SchemaValidationSupport.bareEntityName(table)));
        for (String attr : attrs) {
            SchemaValidationSupport.reportMissingField(attr, table, tableIndex, reportedAttrs, findings, ui, labels);
            if (ts != null && declaredTypes.containsKey(attr)) {
                SchemaValidationSupport.TypeFamily decl =
                        SchemaValidationSupport.typeFamilyFromBsonType(declaredTypes.get(attr));
                var col = SchemaValidationSupport.findColumn(ts, attr);
                if (col != null && col.getDataType() != null && decl != SchemaValidationSupport.TypeFamily.UNKNOWN) {
                    SchemaValidationSupport.TypeFamily colF =
                            SchemaValidationSupport.typeFamilyFromBsonType(col.getDataType());
                    if (colF != SchemaValidationSupport.TypeFamily.UNKNOWN && colF != decl) {
                        SchemaValidationSupport.reportTypeMismatch(
                                attr, colF, decl, reportedTypes, findings, ui);
                    }
                }
            }
        }
    }

    private static boolean isDynamoKeyword(String id) {
        return switch (id.toUpperCase(Locale.ROOT)) {
            case "AND", "OR", "NOT", "BETWEEN", "IN", "SET", "REMOVE", "ADD", "DELETE", "IF", "THEN", "ELSE" -> true;
            default -> false;
        };
    }

    private static void collectJsonTableNames(JsonNode node, Set<String> tables) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey();
                if ("TableName".equalsIgnoreCase(k) || "TargetTableName".equalsIgnoreCase(k)
                        || "SourceTableArn".equalsIgnoreCase(k)) {
                    String v = e.getValue().asText("");
                    if (k.toLowerCase(Locale.ROOT).contains("arn")) {
                        int idx = v.indexOf("/table/");
                        if (idx >= 0) {
                            int end = v.indexOf('/', idx + 7);
                            tables.add(end > idx ? v.substring(idx + 7, end) : v.substring(idx + 7));
                        }
                    } else if (!v.isBlank()) {
                        tables.add(v);
                    }
                }
                collectJsonTableNames(e.getValue(), tables);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectJsonTableNames(child, tables);
            }
        }
    }

    private static String text(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String k : keys) {
            JsonNode n = node.get(k);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText();
            }
        }
        return null;
    }

    private static Map<String, String> readStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                m.put(e.getKey(), e.getValue().asText());
            }
        });
        return m;
    }

    private static Map<String, String> readTypeMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isTextual()) {
                m.put(e.getKey(), e.getValue().asText());
            }
        });
        return m;
    }
}
