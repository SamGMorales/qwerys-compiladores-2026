package com.qwerys.qwerys_backend.exception;

import com.qwerys.qwerys_backend.adapter.DatabaseAdapterFactory;

/**
 * Thrown when the requested database type is not supported by QWERYS.
 * Maps to HTTP 400 Bad Request.
 */
public class UnsupportedDatabaseException extends RuntimeException {

    public UnsupportedDatabaseException(String databaseType) {
        super("Database type not supported: " + databaseType
                + ". Supported types are: " + DatabaseAdapterFactory.supportedDbTypesCsv() + ".");
    }

    public UnsupportedDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
