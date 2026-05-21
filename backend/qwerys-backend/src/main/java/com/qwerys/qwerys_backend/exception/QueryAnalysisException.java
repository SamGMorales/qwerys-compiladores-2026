package com.qwerys.qwerys_backend.exception;

/**
 * Thrown when a query cannot be analyzed due to invalid syntax or unsupported constructs.
 * Maps to HTTP 422 Unprocessable Entity.
 */
public class QueryAnalysisException extends RuntimeException {

    public QueryAnalysisException(String message) {
        super(message);
    }

    public QueryAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
