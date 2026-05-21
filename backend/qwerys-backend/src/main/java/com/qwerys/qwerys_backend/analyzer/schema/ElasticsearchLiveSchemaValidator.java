package com.qwerys.qwerys_backend.analyzer.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerys.qwerys_backend.adapter.ColumnSchema;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live-schema validation for Elasticsearch Query DSL (index, mapping fields, type hints).
 */
public final class ElasticsearchLiveSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> FIELD_CONTAINER_KEYS = Set.of(
            "term", "terms", "match", "match_phrase", "match_phrase_prefix", "multi_match",
            "range", "exists", "prefix", "wildcard", "regexp", "fuzzy", "ids", "geo_distance",
            "geo_bounding_box", "geo_shape", "nested", "has_child", "has_parent", "constant_score");

    private static final Set<String> SKIP_FIELD_NAMES = Set.of(
            "query", "bool", "filter", "must", "should", "must_not", "boost", "minimum_should_match",
            "sort", "aggs", "aggregations", "size", "from", "_source", "script", "params");

    private ElasticsearchLiveSchemaValidator() {
    }

    public static void validate(
            String query,
            String targetIndex,
            DatabaseSchema schema,
            Locale ui,
            List<SemanticError> findings) {
        if (schema == null) {
            return;
        }
        Map<String, TableSchema> tableIndex = SchemaValidationSupport.buildTableIndex(schema);
        if (tableIndex.isEmpty()) {
            return;
        }
        SchemaEntityLabels labels = SchemaEntityLabels.ELASTICSEARCH;
        Set<String> reportedEntities = new HashSet<>();
        Set<String> reportedFields = new HashSet<>();
        Set<String> reportedTypes = new HashSet<>();

        if (targetIndex != null && !targetIndex.isBlank()) {
            SchemaValidationSupport.reportMissingEntity(
                    targetIndex, tableIndex, reportedEntities, findings, ui, labels);
        }

        if (query == null || query.isBlank()) {
            return;
        }
        String index = targetIndex != null && !targetIndex.isBlank()
                ? SchemaValidationSupport.bareEntityName(targetIndex)
                : null;
        if (index == null || !SchemaValidationSupport.tableExists(index, tableIndex)) {
            return;
        }
        TableSchema ts = tableIndex.get(SchemaValidationSupport.normalizeKey(index));

        JsonNode root;
        try {
            root = MAPPER.readTree(query);
        } catch (Exception ignored) {
            return;
        }
        Map<String, JsonNode> fieldContexts = new LinkedHashMap<>();
        collectQueryFields(root, "", fieldContexts);
        collectSortAndSourceFields(root, fieldContexts);

        for (Map.Entry<String, JsonNode> e : fieldContexts.entrySet()) {
            String field = e.getKey();
            if (field.isBlank() || field.startsWith("_") || SKIP_FIELD_NAMES.contains(field)) {
                continue;
            }
            SchemaValidationSupport.reportMissingField(field, index, tableIndex, reportedFields, findings, ui, labels);
            checkEsTypeMismatch(field, e.getValue(), ts, index, reportedTypes, findings, ui);
        }
    }

    private static void collectSortAndSourceFields(JsonNode root, Map<String, JsonNode> out) {
        JsonNode sort = root.get("sort");
        if (sort != null) {
            if (sort.isArray()) {
                for (JsonNode s : sort) {
                    if (s.isTextual()) {
                        out.put(s.asText(), s);
                    } else if (s.isObject()) {
                        s.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
                    }
                }
            }
        }
        JsonNode source = root.get("_source");
        if (source != null && source.isArray()) {
            for (JsonNode f : source) {
                if (f.isTextual()) {
                    out.put(f.asText(), f);
                }
            }
        }
    }

    private static void collectQueryFields(JsonNode node, String prefix, Map<String, JsonNode> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode val = e.getValue();
                if (FIELD_CONTAINER_KEYS.contains(key) && val.isObject()) {
                    val.fields().forEachRemaining(fe -> {
                        String fn = fe.getKey();
                        if (!fn.startsWith("_") && !SKIP_FIELD_NAMES.contains(fn)) {
                            String path = prefix.isEmpty() ? fn : prefix + "." + fn;
                            out.put(path, fe.getValue());
                            if (fe.getValue().isObject()) {
                                collectRangeOrTermValue(path, key, fe.getValue(), out);
                            }
                        }
                    });
                }
                if ("query".equals(key) || "bool".equals(key) || "filter".equals(key)
                        || "must".equals(key) || "should".equals(key) || "must_not".equals(key)
                        || "aggs".equals(key) || "aggregations".equals(key)) {
                    collectQueryFields(val, prefix, out);
                }
                if (val.isObject() || val.isArray()) {
                    collectQueryFields(val, prefix, out);
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectQueryFields(child, prefix, out);
            }
        }
    }

    private static void collectRangeOrTermValue(String fieldPath, String clause, JsonNode fieldNode, Map<String, JsonNode> out) {
        if ("range".equals(clause) && fieldNode.isObject()) {
            out.put(fieldPath + "|range", fieldNode);
        }
        if ("term".equals(clause) && fieldNode.isObject() && fieldNode.has("value")) {
            out.put(fieldPath + "|term", fieldNode.get("value"));
        }
    }

    private static void checkEsTypeMismatch(
            String field,
            JsonNode context,
            TableSchema ts,
            String index,
            Set<String> reportedTypes,
            List<SemanticError> findings,
            Locale ui) {
        String rootField = SchemaValidationSupport.fieldPart(field);
        ColumnSchema cs = SchemaValidationSupport.findColumn(ts, rootField);
        if (cs == null || cs.getDataType() == null) {
            return;
        }
        SchemaValidationSupport.TypeFamily colFamily =
                SchemaValidationSupport.typeFamilyFromEsMapping(cs.getDataType());
        if (colFamily == SchemaValidationSupport.TypeFamily.UNKNOWN) {
            return;
        }
        SchemaValidationSupport.TypeFamily litFamily = SchemaValidationSupport.TypeFamily.UNKNOWN;
        if (context != null) {
            if (context.isTextual()) {
                litFamily = SchemaValidationSupport.TypeFamily.STRING;
            } else if (context.isNumber()) {
                litFamily = context.isIntegralNumber()
                        ? SchemaValidationSupport.TypeFamily.INTEGER
                        : SchemaValidationSupport.TypeFamily.DECIMAL;
            } else if (context.isBoolean()) {
                litFamily = SchemaValidationSupport.TypeFamily.BOOLEAN;
            } else if (context.isObject()) {
                if (context.has("gte") || context.has("gt") || context.has("lte") || context.has("lt")) {
                    JsonNode n = context.has("gte") ? context.get("gte")
                            : context.has("gt") ? context.get("gt") : context.get("lte");
                    if (n != null && n.isNumber()) {
                        litFamily = SchemaValidationSupport.TypeFamily.INTEGER;
                    } else if (n != null && n.isTextual()) {
                        litFamily = SchemaValidationSupport.TypeFamily.STRING;
                    }
                }
                if (context.has("value")) {
                    JsonNode v = context.get("value");
                    if (v.isTextual()) {
                        litFamily = SchemaValidationSupport.TypeFamily.STRING;
                    } else if (v.isNumber()) {
                        litFamily = SchemaValidationSupport.TypeFamily.INTEGER;
                    }
                }
            }
        }
        if (litFamily != SchemaValidationSupport.TypeFamily.UNKNOWN && litFamily != colFamily) {
            SchemaValidationSupport.reportTypeMismatch(field, colFamily, litFamily, reportedTypes, findings, ui);
        }
    }
}
