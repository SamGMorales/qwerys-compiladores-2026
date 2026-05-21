package com.qwerys.qwerys_backend.adapter;

import java.util.List;
import java.util.Map;

/**
 * Shared stub behavior for adapters not yet wired to a driver (Days 26–28 roadmap).
 */
abstract class AbstractDatabaseAdapterStub implements DatabaseAdapter {

    private static final String NOT_YET = "Not yet implemented";

    @Override
    public boolean testConnection(DatabaseConfig config) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public List<String> getTableNames(DatabaseConfig config) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public List<String> getColumnNames(DatabaseConfig config, String tableName) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public Map<String, String> getColumnTypes(DatabaseConfig config, String tableName) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public boolean tableExists(DatabaseConfig config, String tableName) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public QueryExecutionResult executeQuery(DatabaseConfig config, String query) {
        throw new UnsupportedOperationException(NOT_YET);
    }

    @Override
    public DatabaseSchema getSchema(DatabaseConfig config) {
        throw new UnsupportedOperationException(NOT_YET);
    }
}
