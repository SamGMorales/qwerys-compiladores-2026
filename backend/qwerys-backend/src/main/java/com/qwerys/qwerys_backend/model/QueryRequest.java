package com.qwerys.qwerys_backend.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qwerys.qwerys_backend.adapter.DatabaseConfig;

public record QueryRequest(

    String query,

    String databaseType,

    // Valores válidos: mysql, postgresql, sqlite, sqlserver, oracle,
    //                  mongodb, redis, cassandra, dynamodb, elasticsearch

    String queryType,

    // Valores válidos: sql, mongodb, redis, cassandra,
    //                  dynamodb, dynamodb-expression, elasticsearch

    String dialect,

    // Puede ser null. Solo se usa con motores SQL (mysql, postgresql, sqlite, sqlserver, oracle)

    // Idioma de la UI (BCP 47: "es", "en"). Null ⇒ inglés por defecto en mensajes de error del servidor.

    String locale,

    // Conexión opcional a la BD real para validar tablas/columnas contra el esquema (motores SQL JDBC).

    DatabaseConfig connection,

    /** Motor base cuando {@link #databaseType} es {@code custom} o {@code custom::…} (p. ej. mysql, mongodb). */
    @JsonProperty("customEngineBase")
    String customEngineBase
) {}
