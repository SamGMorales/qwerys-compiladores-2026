package com.qwerys.qwerys_backend.analyzer;

/**
 * Represents a single semantic finding produced by {@link SemanticAnalyzer}.
 *
 * @param code       Unique rule identifier (e.g. "SE001").
 * @param message    Human-readable description of the finding.
 * @param suggestion Actionable advice on how to address the finding.
 * @param severity   Severity level: ERROR, WARNING, or INFO.
 * @param line       Optional 1-based source line when the finding is tied to a position (e.g. JSON, lexer).
 * @param column     Optional 1-based source column when available.
 */
public record SemanticError(
        String code,
        String message,
        String suggestion,
        Severity severity,
        Integer line,
        Integer column
) {

    public SemanticError(String code, String message, String suggestion, Severity severity) {
        this(code, message, suggestion, severity, null, null);
    }

    public enum Severity {
        /** Structural or security issue that must be fixed. */
        ERROR,
        /** Potentially dangerous or unintended behaviour. */
        WARNING,
        /** Advisory note about style or performance. */
        INFO
    }

    @Override
    public String toString() {
        String base = "[%s] %s – %s (%s)".formatted(severity, code, message, suggestion);
        if (line != null && column != null) {
            return base + " [@" + line + ":" + column + "]";
        }
        if (line != null) {
            return base + " [line " + line + "]";
        }
        return base;
    }
}
