package com.qwerys.qwerys_backend.adapter;

import com.qwerys.qwerys_backend.exception.UnsupportedDatabaseException;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Resolves a {@link DatabaseAdapter} for a QWERYS-supported engine key.
 */
public final class DatabaseAdapterFactory {

    private static final Map<String, Supplier<DatabaseAdapter>> ADAPTERS =
            Map.ofEntries(
                    Map.entry("cassandra", CassandraAdapter::new),
                    Map.entry("dynamodb", DynamoDBAdapter::new),
                    Map.entry("elasticsearch", ElasticsearchAdapter::new),
                    Map.entry("mongodb", MongoDBAdapter::new),
                    Map.entry("mysql", MySQLAdapter::new),
                    Map.entry("oracle", OracleAdapter::new),
                    Map.entry("postgresql", PostgreSQLAdapter::new),
                    Map.entry("redis", RedisAdapter::new),
                    Map.entry("sqlite", SQLiteAdapter::new),
                    Map.entry("sqlserver", SqlServerAdapter::new));

    private DatabaseAdapterFactory() {
    }

    /**
     * Comma-separated engine ids (lowercase) for error messages; kept in sync with {@link #getAdapter(String)}.
     */
    public static String supportedDbTypesCsv() {
        return ADAPTERS.keySet().stream()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    /**
     * @param dbType engine id (e.g. {@code mysql}); matched case-insensitively after trim
     */
    public static DatabaseAdapter getAdapter(String dbType) {
        if (dbType == null) {
            throw new UnsupportedDatabaseException(
                    "Motor 'null' no soportado. Motores admitidos: " + supportedDbTypesCsv() + ".", null);
        }
        String t = dbType.strip().toLowerCase(Locale.ROOT);
        Supplier<DatabaseAdapter> supplier = ADAPTERS.get(t);
        if (supplier == null && t.startsWith("custom::")) {
            // Motores personalizados — mismo adapter que la base (Days 42-43 pueden refinar la etiqueta).
            String baseEngine = resolveCustomFallbackEngine(t);
            Supplier<DatabaseAdapter> fallback = ADAPTERS.get(baseEngine);
            if (fallback != null) {
                return fallback.get();
            }
            Supplier<DatabaseAdapter> mysql = ADAPTERS.get("mysql");
            if (mysql != null) {
                return mysql.get();
            }
        }
        if (supplier == null) {
            throw new UnsupportedDatabaseException(
                    "Motor '" + dbType + "' no soportado. Motores admitidos: " + supportedDbTypesCsv() + ".",
                    null);
        }
        return supplier.get();
    }

    /**
     * Id esperado: {@code custom::<NombreMotor>::<motorBase>} (ej. {@code custom::CorpPG::postgresql});
     * el último segmento tras {@code ::} es la clave de adapter conocido.
     */
    private static String resolveCustomFallbackEngine(String normalizedCustomId) {
        String[] parts = normalizedCustomId.split("::", -1);
        if (parts.length > 2) {
            String base = parts[parts.length - 1].strip();
            if (!base.isEmpty()) {
                return base;
            }
        }
        return "mysql";
    }
}
