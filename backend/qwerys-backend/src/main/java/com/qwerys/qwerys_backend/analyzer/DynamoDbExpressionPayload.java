package com.qwerys.qwerys_backend.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Input envelope for {@link DynamoDbExpressionAnalyzer}, typically deserialized from the
 * {@code query} field as JSON. Plain text (non-JSON) infers {@link Kind#UPDATE} when the string
 * starts with an update clause ({@code SET}/{@code REMOVE}/{@code ADD}/{@code DELETE}); otherwise
 * {@link Kind#FILTER} for condition-style input. Key-condition rules require JSON metadata.
 */
public final class DynamoDbExpressionPayload {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Kind {
        KEY_CONDITION,
        FILTER,
        PROJECTION,
        UPDATE,
        CONDITION,
        UNKNOWN
    }

    private final Kind kind;
    private final String expression;
    private final Map<String, String> expressionAttributeNames;
    private final Map<String, JsonNode> expressionAttributeValues;
    private final String partitionKeyAttributeName;
    private final String sortKeyAttributeName;
    /** Logical attribute name -> single-letter DynamoDB type when known ({@code S}, {@code N}, {@code M}, …). */
    private final Map<String, String> attributeTypes;

    public DynamoDbExpressionPayload(
            Kind kind,
            String expression,
            Map<String, String> expressionAttributeNames,
            Map<String, JsonNode> expressionAttributeValues,
            String partitionKeyAttributeName,
            String sortKeyAttributeName,
            Map<String, String> attributeTypes) {
        this.kind = kind != null ? kind : Kind.UNKNOWN;
        this.expression = expression != null ? expression : "";
        this.expressionAttributeNames = expressionAttributeNames != null
                ? Collections.unmodifiableMap(new HashMap<>(expressionAttributeNames))
                : Map.of();
        this.expressionAttributeValues = expressionAttributeValues != null
                ? Collections.unmodifiableMap(new HashMap<>(expressionAttributeValues))
                : Map.of();
        this.partitionKeyAttributeName = partitionKeyAttributeName != null ? partitionKeyAttributeName.trim() : null;
        this.sortKeyAttributeName = sortKeyAttributeName != null ? sortKeyAttributeName.trim() : null;
        this.attributeTypes = attributeTypes != null
                ? Collections.unmodifiableMap(new HashMap<>(attributeTypes))
                : Map.of();
    }

    public Kind kind() {
        return kind;
    }

    public String expression() {
        return expression;
    }

    public Map<String, String> expressionAttributeNames() {
        return expressionAttributeNames;
    }

    public Map<String, JsonNode> expressionAttributeValues() {
        return expressionAttributeValues;
    }

    public String partitionKeyAttributeName() {
        return partitionKeyAttributeName;
    }

    public String sortKeyAttributeName() {
        return sortKeyAttributeName;
    }

    public Map<String, String> attributeTypes() {
        return attributeTypes;
    }

    /**
     * Parses {@code raw} from the API. If it looks like JSON, fields are read; otherwise the entire
     * string is the expression and {@link Kind#FILTER} or {@link Kind#UPDATE} is inferred.
     */
    public static DynamoDbExpressionPayload parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new DynamoDbExpressionPayload(Kind.UNKNOWN, "", Map.of(), Map.of(), null, null, Map.of());
        }
        String s = raw.strip();
        if (!s.startsWith("{")) {
            Kind plainKind = inferKindFromPlainExpression(s);
            return new DynamoDbExpressionPayload(plainKind, s, Map.of(), Map.of(), null, null, Map.of());
        }
        try {
            JsonNode root = MAPPER.readTree(s);
            Kind kind = parseKind(text(root, "kind", "expressionKind", "type"));
            String expr = text(root, "expression", "Expression", "expr");
            if (expr == null) {
                expr = "";
            }
            Map<String, String> names = readStringMap(root.get("expressionAttributeNames"));
            if (names.isEmpty()) {
                names = readStringMap(root.get("names"));
            }
            Map<String, JsonNode> values = readJsonMap(root.get("expressionAttributeValues"));
            if (values.isEmpty()) {
                values = readJsonMap(root.get("values"));
            }
            String pk = text(root,
                    "partitionKeyAttributeName",
                    "partitionKey",
                    "partitionKeyName",
                    "pk");
            String sk = text(root, "sortKeyAttributeName", "sortKey", "sortKeyName", "sk");
            Map<String, String> types = readTypeMap(root.get("attributeTypes"));

            if (kind == Kind.UNKNOWN) {
                kind = Kind.FILTER;
            }
            return new DynamoDbExpressionPayload(kind, expr, names, values, pk, sk, types);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid DynamoDB expression JSON: " + e.getMessage(), e);
        }
    }

    private static final Pattern UPDATE_LEADING_CLAUSE = Pattern.compile(
            "(?is)^(SET|REMOVE|ADD|DELETE)\\b");

    /**
     * Heuristic for pasted SDK strings: update expressions always start with one of the clause
     * keywords; condition strings do not.
     */
    static Kind inferKindFromPlainExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            return Kind.FILTER;
        }
        String s = expression.stripLeading();
        if (UPDATE_LEADING_CLAUSE.matcher(s).find()) {
            return Kind.UPDATE;
        }
        return Kind.FILTER;
    }

    private static Kind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return Kind.UNKNOWN;
        }
        String u = raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (u.contains("KEY_CONDITION") || u.equals("KEYCONDITION") || u.equals("QUERY_KEY")) {
            return Kind.KEY_CONDITION;
        }
        if (u.contains("FILTER")) {
            return Kind.FILTER;
        }
        if (u.contains("PROJECTION")) {
            return Kind.PROJECTION;
        }
        if (u.contains("UPDATE")) {
            return Kind.UPDATE;
        }
        if (u.contains("CONDITION") && !u.contains("KEY")) {
            return Kind.CONDITION;
        }
        return Kind.UNKNOWN;
    }

    private static String text(JsonNode root, String... keys) {
        for (String k : keys) {
            JsonNode n = root.get(k);
            if (n != null && !n.isNull() && n.isTextual()) {
                String t = n.asText();
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        return null;
    }

    private static Map<String, String> readStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> m = new HashMap<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            JsonNode v = node.get(k);
            if (v != null && v.isTextual()) {
                m.put(k, v.asText());
            }
        }
        return m;
    }

    private static Map<String, JsonNode> readJsonMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, JsonNode> m = new HashMap<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, node.get(k));
        }
        return m;
    }

    private static Map<String, String> readTypeMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> m = new HashMap<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            JsonNode v = node.get(k);
            if (v != null && v.isTextual()) {
                m.put(k, v.asText().trim().toUpperCase(Locale.ROOT));
            }
        }
        return m;
    }
}
