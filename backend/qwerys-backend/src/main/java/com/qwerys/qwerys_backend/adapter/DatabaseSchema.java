package com.qwerys.qwerys_backend.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Logical schema snapshot for a database (engine-agnostic shape).
 */
public class DatabaseSchema {

    private String dbType;
    private String databaseName;
    private List<TableSchema> tables;

    public DatabaseSchema() {
        this.tables = new ArrayList<>();
    }

    public DatabaseSchema(String dbType, String databaseName, List<TableSchema> tables) {
        this.dbType = dbType;
        this.databaseName = databaseName;
        this.tables = tables != null ? new ArrayList<>(tables) : new ArrayList<>();
    }

    public String getDbType() {
        return dbType;
    }

    public void setDbType(String dbType) {
        this.dbType = dbType;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public List<TableSchema> getTables() {
        return tables;
    }

    public void setTables(List<TableSchema> tables) {
        this.tables = tables != null ? new ArrayList<>(tables) : new ArrayList<>();
    }
}
