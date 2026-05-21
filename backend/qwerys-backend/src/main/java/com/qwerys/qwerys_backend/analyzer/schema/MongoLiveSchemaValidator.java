package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.adapter.ColumnSchema;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.MongoDbLexer;
import com.qwerys.qwerys_backend.analyzer.NoSqlToken;
import com.qwerys.qwerys_backend.analyzer.NoSqlTokenType;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.StatementSplitter;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.schema.MongoShellTokenNav.KeyVal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live-schema validation for MongoDB shell queries: collections (including $lookup),
 * document fields, aggregation paths, and basic type checks.
 */
public final class MongoLiveSchemaValidator {

    private static final Pattern DB_COLLECTION = Pattern.compile("\\bdb\\.([A-Za-z_][A-Za-z0-9_]*)\\.");

    private static final Set<String> MONGO_RESERVED = Set.of(
            "_id", "null", "true", "false", "ObjectId", "ISODate", "NumberInt", "NumberLong",
            "NumberDecimal", "BinData", "Timestamp", "UUID", "DBRef", "MinKey", "MaxKey",
            "from", "as", "localField", "foreignField", "pipeline", "let");

    private MongoLiveSchemaValidator() {
    }

    public static void validate(String rawQuery, DatabaseSchema schema, Locale ui, List<SemanticError> findings) {
        if (rawQuery == null || rawQuery.isBlank() || schema == null) {
            return;
        }
        Map<String, TableSchema> tableIndex = SchemaValidationSupport.buildTableIndex(schema);
        if (tableIndex.isEmpty()) {
            return;
        }
        SchemaEntityLabels labels = SchemaEntityLabels.MONGODB;
        Set<String> reportedEntities = new HashSet<>();
        Set<String> reportedFields = new HashSet<>();
        Set<String> reportedTypes = new HashSet<>();

        for (String chunk : StatementSplitter.split(rawQuery, SqlDialect.GENERIC)) {
            String one = chunk.strip();
            if (one.isEmpty()) {
                continue;
            }
            Matcher dbm = DB_COLLECTION.matcher(one);
            while (dbm.find()) {
                SchemaValidationSupport.reportMissingEntity(
                        dbm.group(1), tableIndex, reportedEntities, findings, ui, labels);
            }
            if (!one.regionMatches(true, 0, "db.", 0, 3)) {
                continue;
            }
            try {
                List<NoSqlToken> tokens = new MongoDbLexer(one, ui).tokenize();
                Map<String, Set<String>> fieldsByCollection = extractFieldsByCollection(tokens, one);
                Set<String> collections = new LinkedHashSet<>(fieldsByCollection.keySet());
                for (NoSqlToken t : tokens) {
                    if (t.type() == NoSqlTokenType.COLLECTION_NAME) {
                        collections.add(t.value());
                    }
                }
                for (String coll : collections) {
                    SchemaValidationSupport.reportMissingEntity(
                            coll, tableIndex, reportedEntities, findings, ui, labels);
                    if (!SchemaValidationSupport.tableExists(coll, tableIndex)) {
                        continue;
                    }
                    TableSchema ts = tableIndex.get(SchemaValidationSupport.normalizeKey(
                            SchemaValidationSupport.bareEntityName(coll)));
                    Set<String> fields = fieldsByCollection.getOrDefault(coll, Set.of());
                    for (String field : fields) {
                        if (field == null || field.isBlank() || MONGO_RESERVED.contains(field)) {
                            continue;
                        }
                        SchemaValidationSupport.reportMissingField(
                                field, coll, tableIndex, reportedFields, findings, ui, labels);
                        checkFieldLiteralMismatch(field, tokens, ts, coll, reportedTypes, findings, ui);
                    }
                }
                collectDollarPathFields(tokens, fieldsByCollection, tableIndex, reportedFields, reportedTypes, findings, ui, labels);
            } catch (MongoDbLexer.LexException ignored) {
                // Lex errors handled by MongoDbAnalyzer
            }
        }
    }

    private static Map<String, Set<String>> extractFieldsByCollection(List<NoSqlToken> tokens, String raw) {
        Map<String, Set<String>> map = new LinkedHashMap<>();
        String primary = null;
        for (NoSqlToken t : tokens) {
            if (t.type() == NoSqlTokenType.COLLECTION_NAME) {
                primary = t.value();
                map.computeIfAbsent(primary, k -> new LinkedHashSet<>());
                break;
            }
        }
        if (primary == null) {
            Matcher m = DB_COLLECTION.matcher(raw);
            if (m.find()) {
                primary = m.group(1);
                map.computeIfAbsent(primary, k -> new LinkedHashSet<>());
            }
        }
        if (primary != null) {
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).type() == NoSqlTokenType.BRACE_OPEN) {
                    collectObjectKeysDeep(tokens, i, map.computeIfAbsent(primary, k -> new LinkedHashSet<>()));
                }
            }
        }
        int agg = MongoShellTokenNav.indexOfMethod(tokens, NoSqlTokenType.AGGREGATE);
        if (agg >= 0) {
            int pipe = MongoShellTokenNav.firstPipelineBracket(tokens, agg);
            if (pipe >= 0) {
                for (int stageOpen : MongoShellTokenNav.collectStageBraceOpens(tokens, pipe)) {
                    for (KeyVal stage : MongoShellTokenNav.parseTopLevelObjectPairs(tokens, stageOpen)) {
                        if ("$lookup".equals(stage.key()) || "$graphLookup".equals(stage.key())) {
                            extractLookupCollection(tokens, stage.valStart(), map);
                        }
                        String coll = primary != null ? primary : "";
                        Set<String> stageFields = map.computeIfAbsent(coll, k -> new LinkedHashSet<>());
                        collectStageFieldRefs(tokens, stage, stageFields);
                    }
                }
            }
        }
        map.keySet().removeIf(String::isBlank);
        return map;
    }

    private static void extractLookupCollection(List<NoSqlToken> tokens, int valStart, Map<String, Set<String>> map) {
        if (valStart >= tokens.size() || tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return;
        }
        for (KeyVal kv : MongoShellTokenNav.parseTopLevelObjectPairs(tokens, valStart)) {
            if ("from".equals(kv.key()) && kv.valStart() < tokens.size()) {
                NoSqlToken v = tokens.get(kv.valStart());
                if (v.type() == NoSqlTokenType.STRING || v.type() == NoSqlTokenType.FIELD_NAME) {
                    map.computeIfAbsent(v.value(), k -> new LinkedHashSet<>());
                }
            }
        }
    }

    private static void collectStageFieldRefs(List<NoSqlToken> tokens, KeyVal stage, Set<String> fields) {
        if (stage.valStart() >= tokens.size()) {
            return;
        }
        NoSqlTokenType vt = tokens.get(stage.valStart()).type();
        if (vt == NoSqlTokenType.STRING && tokens.get(stage.valStart()).value().startsWith("$")) {
            addDollarPathRoot(tokens.get(stage.valStart()).value(), fields);
        }
        if (vt == NoSqlTokenType.BRACE_OPEN) {
            collectObjectKeysDeep(tokens, stage.valStart(), fields);
            for (KeyVal inner : MongoShellTokenNav.parseTopLevelObjectPairs(tokens, stage.valStart())) {
                if (inner.valStart() < tokens.size() && tokens.get(inner.valStart()).type() == NoSqlTokenType.STRING) {
                    String s = tokens.get(inner.valStart()).value();
                    if (s.startsWith("$")) {
                        addDollarPathRoot(s, fields);
                    }
                }
            }
        }
    }

    private static void collectDollarPathFields(
            List<NoSqlToken> tokens,
            Map<String, Set<String>> fieldsByCollection,
            Map<String, TableSchema> tableIndex,
            Set<String> reportedFields,
            Set<String> reportedTypes,
            List<SemanticError> findings,
            Locale ui,
            SchemaEntityLabels labels) {
        String primary = fieldsByCollection.keySet().stream().findFirst().orElse(null);
        if (primary == null || !SchemaValidationSupport.tableExists(primary, tableIndex)) {
            return;
        }
        TableSchema ts = tableIndex.get(SchemaValidationSupport.normalizeKey(
                SchemaValidationSupport.bareEntityName(primary)));
        Set<String> paths = new LinkedHashSet<>();
        for (NoSqlToken t : tokens) {
            if (t.type() == NoSqlTokenType.STRING && t.value().startsWith("$")) {
                String v = t.value();
                if (v.length() > 1 && !v.startsWith("$[")) {
                    addDollarPathRoot(v, paths);
                }
            }
        }
        for (String path : paths) {
            String root = SchemaValidationSupport.fieldPart(path);
            if (MONGO_RESERVED.contains(root)) {
                continue;
            }
            SchemaValidationSupport.reportMissingField(path, primary, tableIndex, reportedFields, findings, ui, labels);
        }
    }

    private static void addDollarPathRoot(String dollarPath, Set<String> fields) {
        if (dollarPath == null || !dollarPath.startsWith("$")) {
            return;
        }
        String p = dollarPath.substring(1);
        int dot = p.indexOf('.');
        fields.add(dot >= 0 ? p.substring(0, dot) : p);
    }

    private static void collectObjectKeysDeep(List<NoSqlToken> tokens, int braceOpen, Set<String> fields) {
        int depth = 0;
        for (int i = braceOpen; i < tokens.size(); i++) {
            NoSqlToken t = tokens.get(i);
            if (t.type() == NoSqlTokenType.BRACE_OPEN) {
                depth++;
            } else if (t.type() == NoSqlTokenType.BRACE_CLOSE) {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
            if (depth >= 1 && i + 1 < tokens.size() && tokens.get(i + 1).type() == NoSqlTokenType.COLON) {
                String key = objectKeyName(t);
                if (key != null && !key.startsWith("$") && !MONGO_RESERVED.contains(key)) {
                    fields.add(SchemaValidationSupport.fieldPart(key));
                }
            }
        }
    }

    private static String objectKeyName(NoSqlToken t) {
        return switch (t.type()) {
            case STRING, FIELD_NAME -> t.value();
            default -> null;
        };
    }

    private static void checkFieldLiteralMismatch(
            String field,
            List<NoSqlToken> tokens,
            TableSchema ts,
            String collection,
            Set<String> reportedTypes,
            List<SemanticError> findings,
            Locale ui) {
        ColumnSchema cs = SchemaValidationSupport.findColumn(ts, field);
        if (cs == null || cs.getDataType() == null) {
            return;
        }
        SchemaValidationSupport.TypeFamily colFamily =
                SchemaValidationSupport.typeFamilyFromBsonType(cs.getDataType());
        if (colFamily == SchemaValidationSupport.TypeFamily.UNKNOWN) {
            return;
        }
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (!field.equals(objectKeyName(tokens.get(i))) || tokens.get(i + 1).type() != NoSqlTokenType.COLON) {
                continue;
            }
            NoSqlToken val = tokens.get(i + 2);
            SchemaValidationSupport.TypeFamily litFamily = literalFamily(val);
            if (litFamily == SchemaValidationSupport.TypeFamily.UNKNOWN || litFamily == colFamily) {
                continue;
            }
            String key = collection + "|" + field + "|" + colFamily + "|" + litFamily;
            if (reportedTypes.add(key)) {
                SchemaValidationSupport.reportTypeMismatch(field, colFamily, litFamily, reportedTypes, findings, ui);
            }
        }
    }

    private static SchemaValidationSupport.TypeFamily literalFamily(NoSqlToken val) {
        return switch (val.type()) {
            case STRING -> SchemaValidationSupport.TypeFamily.STRING;
            case NUMBER -> SchemaValidationSupport.TypeFamily.INTEGER;
            case BOOLEAN -> SchemaValidationSupport.TypeFamily.BOOLEAN;
            default -> SchemaValidationSupport.TypeFamily.UNKNOWN;
        };
    }
}
