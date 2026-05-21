package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.adapter.DatabaseAdapter;
import com.qwerys.qwerys_backend.adapter.DatabaseAdapterFactory;
import com.qwerys.qwerys_backend.adapter.DatabaseConfig;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.exception.UnsupportedDatabaseException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Schema introspection via {@link DatabaseAdapterFactory} — works for every registered engine
 * without coupling to a specific adapter implementation.
 */
@Service
public class SchemaService {

    public boolean testConnection(DatabaseConfig config) {
        try {
            DatabaseAdapter adapter = DatabaseAdapterFactory.getAdapter(config.dbType());
            return adapter.testConnection(config);
        } catch (UnsupportedDatabaseException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    public List<String> getTableNames(DatabaseConfig config) {
        try {
            DatabaseAdapter adapter = DatabaseAdapterFactory.getAdapter(config.dbType());
            return adapter.getTableNames(config);
        } catch (UnsupportedDatabaseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Motor '" + config.dbType() + "' no soportado");
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    e.getMessage() != null ? e.getMessage() : "No se pudo listar tablas o colecciones");
        }
    }

    public TableSchema describeTable(DatabaseConfig config, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre de tabla es obligatorio");
        }
        try {
            DatabaseAdapter adapter = DatabaseAdapterFactory.getAdapter(config.dbType());
            DatabaseSchema schema = adapter.getSchema(config);
            String wanted = tableName.strip();
            return schema.getTables().stream()
                    .filter(t -> tableNameMatches(t.getTableName(), wanted))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found"));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (UnsupportedDatabaseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Motor '" + config.dbType() + "' no soportado");
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    e.getMessage() != null ? e.getMessage() : "No se pudo describir la tabla");
        }
    }

    public DatabaseSchema getFullSchema(DatabaseConfig config) {
        try {
            DatabaseAdapter adapter = DatabaseAdapterFactory.getAdapter(config.dbType());
            return adapter.getSchema(config);
        } catch (UnsupportedDatabaseException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Motor '" + config.dbType() + "' no soportado");
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    e.getMessage() != null ? e.getMessage() : "No se pudo obtener el esquema completo");
        }
    }

    private static boolean tableNameMatches(String schemaName, String requested) {
        if (schemaName == null || requested == null) {
            return false;
        }
        if (Objects.equals(schemaName, requested)) {
            return true;
        }
        return schemaName.toLowerCase(Locale.ROOT).equals(requested.toLowerCase(Locale.ROOT));
    }
}
