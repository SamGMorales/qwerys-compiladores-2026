package com.qwerys.qwerys_backend.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for a single table (or collection-like object in NoSQL adapters).
 */
public class TableSchema {

    private String tableName;
    private List<ColumnSchema> columns;
    private List<String> primaryKeys;
    private List<String> foreignKeys;

    public TableSchema() {
        this.columns = new ArrayList<>();
        this.primaryKeys = new ArrayList<>();
        this.foreignKeys = new ArrayList<>();
    }

    public TableSchema(
            String tableName,
            List<ColumnSchema> columns,
            List<String> primaryKeys,
            List<String> foreignKeys) {
        this.tableName = tableName;
        this.columns = columns != null ? new ArrayList<>(columns) : new ArrayList<>();
        this.primaryKeys = primaryKeys != null ? new ArrayList<>(primaryKeys) : new ArrayList<>();
        this.foreignKeys = foreignKeys != null ? new ArrayList<>(foreignKeys) : new ArrayList<>();
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public List<ColumnSchema> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnSchema> columns) {
        this.columns = columns != null ? new ArrayList<>(columns) : new ArrayList<>();
    }

    public List<String> getPrimaryKeys() {
        return primaryKeys;
    }

    public void setPrimaryKeys(List<String> primaryKeys) {
        this.primaryKeys = primaryKeys != null ? new ArrayList<>(primaryKeys) : new ArrayList<>();
    }

    public List<String> getForeignKeys() {
        return foreignKeys;
    }

    public void setForeignKeys(List<String> foreignKeys) {
        this.foreignKeys = foreignKeys != null ? new ArrayList<>(foreignKeys) : new ArrayList<>();
    }
}
