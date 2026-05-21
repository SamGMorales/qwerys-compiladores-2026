package com.qwerys.qwerys_backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qwerys.qwerys_backend.dto.StudentExplanationDto;

/**
 * A structured error found during query analysis.
 *
 * @param code       Machine-readable error code (e.g. "SE001", "LT001", "ORA002")
 * @param message    Human-readable description of the error
 * @param suggestion Suggested fix for the error
 * @param line       Optional 1-based line in the analyzed source (when known)
 * @param column     Optional 1-based column (when known)
 * @param education  Optional student-mode explanation (only when {@code X-Student-Mode: true})
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisError(
        String code,
        String message,
        String suggestion,
        Integer line,
        Integer column,
        StudentExplanationDto education
) {
    public AnalysisError(String code, String message, String suggestion) {
        this(code, message, suggestion, null, null, null);
    }

    public AnalysisError(String code, String message, String suggestion, Integer line, Integer column) {
        this(code, message, suggestion, line, column, null);
    }
}
