package com.qwerys.qwerys_backend.adapter;

import java.util.List;
import java.util.Map;

/**
 * Pluggable access to live database metadata and execution. One implementation per engine.
 */
public interface DatabaseAdapter {

    boolean testConnection(DatabaseConfig config);

    List<String> getTableNames(DatabaseConfig config);

    List<String> getColumnNames(DatabaseConfig config, String tableName);

    Map<String, String> getColumnTypes(DatabaseConfig config, String tableName);

    boolean tableExists(DatabaseConfig config, String tableName);

    QueryExecutionResult executeQuery(DatabaseConfig config, String query);

    DatabaseSchema getSchema(DatabaseConfig config);

    String getDbType();
}
