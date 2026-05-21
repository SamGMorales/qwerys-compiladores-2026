package com.qwerys.qwerys_backend.adapter;

/**
 * Column (or field) metadata within a {@link TableSchema}.
 */
public class ColumnSchema {

    private String columnName;
    private String dataType;
    private boolean nullable;
    private boolean primaryKey;
    private String defaultValue;

    public ColumnSchema() {
    }

    public ColumnSchema(
            String columnName,
            String dataType,
            boolean nullable,
            boolean primaryKey,
            String defaultValue) {
        this.columnName = columnName;
        this.dataType = dataType;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.defaultValue = defaultValue;
    }

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }
}
