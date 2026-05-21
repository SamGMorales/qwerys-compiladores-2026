package com.qwerys.qwerys_backend.analyzer.schema;

/**
 * Localized entity naming for live-schema validation messages (table vs collection vs key, etc.).
 */
public record SchemaEntityLabels(
        String entitySingularEn,
        String entitySingularEs,
        String entityPluralEn,
        String entityPluralEs,
        String fieldSingularEn,
        String fieldSingularEs) {

    public static final SchemaEntityLabels SQL_TABLE = new SchemaEntityLabels(
            "table", "tabla", "tables", "tablas", "column", "columna");

    public static final SchemaEntityLabels MONGODB = new SchemaEntityLabels(
            "collection", "colección", "collections", "colecciones", "field", "campo");

    public static final SchemaEntityLabels CASSANDRA = SQL_TABLE;

    public static final SchemaEntityLabels DYNAMODB = SQL_TABLE;

    public static final SchemaEntityLabels ELASTICSEARCH = new SchemaEntityLabels(
            "index", "índice", "indices", "índices", "field", "campo");

    public static final SchemaEntityLabels REDIS = new SchemaEntityLabels(
            "key", "clave", "keys", "claves", "hash field", "campo hash");
}
