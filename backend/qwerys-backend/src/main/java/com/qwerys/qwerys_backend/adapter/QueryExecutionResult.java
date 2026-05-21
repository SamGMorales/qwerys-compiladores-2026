package com.qwerys.qwerys_backend.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Outcome of executing a query or command through a {@link DatabaseAdapter}.
 */
public class QueryExecutionResult {

    private boolean success;
    private List<Map<String, Object>> rows;
    private int affectedRows;
    private String errorMessage;
    private long executionTimeMs;

    public QueryExecutionResult() {
        this.rows = new ArrayList<>();
    }

    public QueryExecutionResult(
            boolean success,
            List<Map<String, Object>> rows,
            int affectedRows,
            String errorMessage,
            long executionTimeMs) {
        this.success = success;
        this.rows = rows != null ? new ArrayList<>(rows) : new ArrayList<>();
        this.affectedRows = affectedRows;
        this.errorMessage = errorMessage;
        this.executionTimeMs = executionTimeMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows != null ? new ArrayList<>(rows) : new ArrayList<>();
    }

    public int getAffectedRows() {
        return affectedRows;
    }

    public void setAffectedRows(int affectedRows) {
        this.affectedRows = affectedRows;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }
}
