package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.adapter.DatabaseSchema;

/**
 * Outcome of probing a live database before or during schema-aware analysis.
 */
public record LiveSchemaValidationResult(
        Status status,
        DatabaseSchema schema,
        String message,
        String suggestion
) {
    public enum Status {
        OK,
        CONNECTION_FAILED,
        EMPTY_SCHEMA,
        LOAD_FAILED
    }

    public static LiveSchemaValidationResult ok(DatabaseSchema schema) {
        return new LiveSchemaValidationResult(Status.OK, schema, null, null);
    }

    public boolean isUsable() {
        return status == Status.OK && schema != null;
    }
}
