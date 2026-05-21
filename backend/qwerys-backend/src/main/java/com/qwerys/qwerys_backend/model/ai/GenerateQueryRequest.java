package com.qwerys.qwerys_backend.model.ai;

public record GenerateQueryRequest(
        String operation,
        String tableName,
        java.util.List<String> columns,
        String condition,
        String databaseType,
        String locale
) {
}
