package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.adapter.ColumnSchema;
import com.qwerys.qwerys_backend.adapter.DatabaseAdapter;
import com.qwerys.qwerys_backend.adapter.DatabaseAdapterFactory;
import com.qwerys.qwerys_backend.adapter.DatabaseConfig;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.AnalysisMessages;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for live-schema validation across SQL and NoSQL engines.
 */
public final class SchemaValidationSupport {

    private SchemaValidationSupport() {
    }

    public static DatabaseSchema loadSchema(DatabaseConfig connection) {
        LiveSchemaValidationResult result = validateForAnalysis(connection, Locale.ENGLISH);
        return result.isUsable() ? result.schema() : null;
    }

    public static LiveSchemaValidationResult validateForAnalysis(DatabaseConfig connection, Locale ui) {
        Locale locale = ui != null ? ui : Locale.ENGLISH;
        if (connection == null || connection.dbType() == null || connection.dbType().isBlank()) {
            return connectionFailed(
                    locale,
                    AnalysisMessages.t(
                            locale,
                            "Database connection settings are incomplete.",
                            "La configuración de conexión a la base de datos está incompleta."),
                    AnalysisMessages.t(
                            locale,
                            "Select an engine and fill in the required connection fields.",
                            "Elige un motor y completa los campos de conexión requeridos."));
        }
        try {
            DatabaseAdapter adapter = DatabaseAdapterFactory.getAdapter(connection.dbType());
            if (!adapter.testConnection(connection)) {
                return connectionFailed(
                        locale,
                        AnalysisMessages.t(
                                locale,
                                "Could not connect to the database.",
                                "No se pudo conectar a la base de datos."),
                        AnalysisMessages.t(
                                locale,
                                "Check host, port, credentials, and file path (SQLite).",
                                "Revisa host, puerto, credenciales y ruta del archivo (SQLite)."));
            }
            DatabaseSchema schema = adapter.getSchema(connection);
            if (schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
                return new LiveSchemaValidationResult(
                        LiveSchemaValidationResult.Status.EMPTY_SCHEMA,
                        schema,
                        AnalysisMessages.t(
                                locale,
                                "Connected but no tables or collections were found.",
                                "Conectado, pero no se encontraron tablas o colecciones."),
                        AnalysisMessages.t(
                                locale,
                                "Verify the database name, keyspace, or SQLite file path points to the correct data.",
                                "Verifica el nombre de la base, keyspace o ruta del archivo SQLite correcto."));
            }
            return LiveSchemaValidationResult.ok(schema);
        } catch (Exception ex) {
            String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            return new LiveSchemaValidationResult(
                    LiveSchemaValidationResult.Status.LOAD_FAILED,
                    null,
                    AnalysisMessages.t(
                            locale,
                            "Could not read the live schema: " + detail,
                            "No se pudo leer el esquema en vivo: " + detail),
                    AnalysisMessages.t(
                            locale,
                            "Fix the connection and try again before relying on schema validation.",
                            "Corrige la conexión e inténtalo de nuevo antes de confiar en la validación contra BD."));
        }
    }

    public static List<SemanticError> liveSchemaFindings(LiveSchemaValidationResult validation, Locale ui) {
        if (validation == null || validation.status() == LiveSchemaValidationResult.Status.OK) {
            return List.of();
        }
        Locale locale = ui != null ? ui : Locale.ENGLISH;
        String code = switch (validation.status()) {
            case EMPTY_SCHEMA -> "SCH-EMPTY";
            case CONNECTION_FAILED, LOAD_FAILED -> "SCH-CONN";
            default -> "SCH-CONN";
        };
        SemanticError.Severity severity = validation.status() == LiveSchemaValidationResult.Status.EMPTY_SCHEMA
                ? SemanticError.Severity.WARNING
                : SemanticError.Severity.ERROR;
        return List.of(new SemanticError(
                code,
                validation.message(),
                validation.suggestion(),
                severity));
    }

    private static LiveSchemaValidationResult connectionFailed(Locale ui, String message, String suggestion) {
        return new LiveSchemaValidationResult(
                LiveSchemaValidationResult.Status.CONNECTION_FAILED, null, message, suggestion);
    }

    public static List<SemanticError> appendLiveSchemaFindings(
            List<SemanticError> delegateFindings,
            LiveSchemaValidationResult validation,
            Locale ui) {
        List<SemanticError> merged = new ArrayList<>(delegateFindings != null ? delegateFindings : List.of());
        merged.addAll(liveSchemaFindings(validation, ui));
        return merged;
    }

    public static Map<String, TableSchema> buildTableIndex(DatabaseSchema schema) {
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
        return index;
    }

    public static boolean tableExists(String table, Map<String, TableSchema> tableIndex) {
        return tableIndex.containsKey(normalizeKey(bareEntityName(table)));
    }

    public static ColumnSchema findColumn(TableSchema table, String colName) {
        if (table == null || colName == null) {
            return null;
        }
        for (ColumnSchema c : table.getColumns()) {
            if (c.getColumnName() != null && c.getColumnName().equalsIgnoreCase(colName)) {
                return c;
            }
        }
        return null;
    }

    public static void reportMissingEntity(
            String entity,
            Map<String, TableSchema> tableIndex,
            Set<String> reported,
            List<SemanticError> findings,
            Locale ui,
            SchemaEntityLabels labels) {
        if (tableExists(entity, tableIndex)) {
            return;
        }
        String bare = bareEntityName(entity);
        if (!reported.add(normalizeKey(bare))) {
            return;
        }
        findings.add(new SemanticError(
                "SCH-001",
                AnalysisMessages.t(ui,
                        labels.entitySingularEn() + " '" + bare + "' does not exist in the connected database",
                        "La " + labels.entitySingularEs() + " '" + bare + "' no existe en la base de datos conectada"),
                AnalysisMessages.t(ui,
                        "Check the " + labels.entitySingularEn() + " name or create it before querying.",
                        "Revise el nombre de la " + labels.entitySingularEs() + " o créela antes de consultarla."),
                SemanticError.Severity.ERROR));
    }

    public static void reportMissingField(
            String fieldRef,
            String entity,
            Map<String, TableSchema> tableIndex,
            Set<String> reported,
            List<SemanticError> findings,
            Locale ui,
            SchemaEntityLabels labels) {
        String bareEntity = bareEntityName(entity);
        TableSchema ts = tableIndex.get(normalizeKey(bareEntity));
        if (ts == null) {
            return;
        }
        String fieldName = fieldPart(fieldRef);
        if (isRedisMetaColumn(fieldName) || findColumn(ts, fieldName) != null) {
            return;
        }
        String key = normalizeKey(bareEntity) + "|" + normalizeKey(fieldName);
        if (!reported.add(key)) {
            return;
        }
        findings.add(new SemanticError(
                "SCH-002",
                AnalysisMessages.t(ui,
                        labels.fieldSingularEn() + " '" + fieldName + "' not found in "
                                + labels.entitySingularEn() + " '" + bareEntity + "'",
                        "El " + labels.fieldSingularEs() + " '" + fieldName + "' no existe en la "
                                + labels.entitySingularEs() + " '" + bareEntity + "'"),
                AnalysisMessages.t(ui,
                        "Verify the " + labels.fieldSingularEn() + " name against the live schema.",
                        "Verifique el nombre del " + labels.fieldSingularEs() + " contra el esquema en vivo."),
                SemanticError.Severity.ERROR));
    }

    public static void reportTypeMismatch(
            String fieldRef,
            TypeFamily colFamily,
            TypeFamily litFamily,
            Set<String> reported,
            List<SemanticError> findings,
            Locale ui) {
        String key = fieldRef + "|" + colFamily + "|" + litFamily;
        if (!reported.add(key)) {
            return;
        }
        String colLabel = simplifiedTypeLabel(colFamily);
        String litLabel = simplifiedTypeLabel(litFamily);
        findings.add(new SemanticError(
                "SCH-003",
                AnalysisMessages.t(ui,
                        "Type mismatch: comparing " + colLabel + " with " + litLabel,
                        "Tipos incompatibles: comparando " + colLabel + " con " + litLabel),
                AnalysisMessages.t(ui,
                        "Cast the literal or field so both sides use a compatible type.",
                        "Convierta el literal o el campo para que ambos lados usen un tipo compatible."),
                SemanticError.Severity.WARNING));
    }

    public static String bareEntityName(String qualified) {
        if (qualified == null) {
            return "";
        }
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }

    public static String fieldPart(String ref) {
        int dot = ref.lastIndexOf('.');
        return dot >= 0 ? ref.substring(dot + 1) : ref;
    }

    public static String normalizeKey(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    public enum TypeFamily {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        DATE,
        UNKNOWN
    }

    public static TypeFamily typeFamilyFromSqlType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return TypeFamily.UNKNOWN;
        }
        String u = dataType.toUpperCase(Locale.ROOT);
        if (u.contains("CHAR") || u.contains("TEXT") || u.contains("CLOB") || u.contains("JSON")
                || u.contains("STRING") || u.contains("UUID") || u.contains("KEYWORD")) {
            return TypeFamily.STRING;
        }
        if (u.contains("BOOL")) {
            return TypeFamily.BOOLEAN;
        }
        if (u.contains("DATE") || u.contains("TIME") || u.contains("TIMESTAMP")) {
            return TypeFamily.DATE;
        }
        if (u.contains("INT") || u.equals("BIGINT") || u.equals("SMALLINT") || u.equals("TINYINT")
                || u.equals("BIT") || u.equals("SERIAL") || u.equals("NUMBER")) {
            return TypeFamily.INTEGER;
        }
        if (u.contains("DEC") || u.contains("NUM") || u.contains("FLOAT") || u.contains("REAL")
                || u.contains("DOUBLE") || u.contains("MONEY")) {
            return TypeFamily.DECIMAL;
        }
        return TypeFamily.UNKNOWN;
    }

    public static boolean isRedisMetaColumn(String colName) {
        return "redisType".equalsIgnoreCase(colName);
    }

    public static TypeFamily typeFamilyFromEsMapping(String mappingType) {
        if (mappingType == null || mappingType.isBlank()) {
            return TypeFamily.UNKNOWN;
        }
        String u = mappingType.toLowerCase(Locale.ROOT);
        if (u.contains("text") || u.contains("keyword") || u.contains("wildcard")) {
            return TypeFamily.STRING;
        }
        if (u.contains("boolean")) {
            return TypeFamily.BOOLEAN;
        }
        if (u.contains("date") || u.contains("time")) {
            return TypeFamily.DATE;
        }
        if (u.contains("long") || u.contains("integer") || u.contains("short") || u.contains("byte")) {
            return TypeFamily.INTEGER;
        }
        if (u.contains("float") || u.contains("double") || u.contains("half_float") || u.contains("scaled_float")) {
            return TypeFamily.DECIMAL;
        }
        return TypeFamily.UNKNOWN;
    }

    public static TypeFamily typeFamilyFromBsonType(String bsonType) {
        if (bsonType == null || bsonType.isBlank()) {
            return TypeFamily.UNKNOWN;
        }
        return switch (bsonType.toLowerCase(Locale.ROOT)) {
            case "string" -> TypeFamily.STRING;
            case "number", "int", "long", "double" -> TypeFamily.INTEGER;
            case "boolean" -> TypeFamily.BOOLEAN;
            case "date" -> TypeFamily.DATE;
            default -> TypeFamily.UNKNOWN;
        };
    }

    public static TypeFamily typeFamilyFromLiteral(String literal) {
        if (literal == null || literal.isBlank()) {
            return TypeFamily.UNKNOWN;
        }
        String v = literal.trim();
        if (v.startsWith("'") || v.startsWith("\"")) {
            return TypeFamily.STRING;
        }
        if ("TRUE".equalsIgnoreCase(v) || "FALSE".equalsIgnoreCase(v)) {
            return TypeFamily.BOOLEAN;
        }
        if (v.matches("-?\\d+")) {
            return TypeFamily.INTEGER;
        }
        if (v.matches("-?\\d+\\.\\d+")) {
            return TypeFamily.DECIMAL;
        }
        return TypeFamily.UNKNOWN;
    }

    private static String simplifiedTypeLabel(TypeFamily family) {
        return switch (family) {
            case STRING -> "VARCHAR";
            case INTEGER -> "INTEGER";
            case DECIMAL -> "DECIMAL";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case UNKNOWN -> "UNKNOWN";
        };
    }
}
