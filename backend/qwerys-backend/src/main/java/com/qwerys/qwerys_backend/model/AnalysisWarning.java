package com.qwerys.qwerys_backend.model;

import com.qwerys.qwerys_backend.analyzer.SemanticError;

/**
 * Non-blocking finding returned with analysis results.
 *
 * @param code     Rule identifier (e.g. {@code RDS-KEYS-002}).
 * @param severity {@code WARNING} (production-relevant) or {@code INFO} (advisory only).
 */
public record AnalysisWarning(String code, String severity) {

    public AnalysisWarning {
        if (severity == null || severity.isBlank()) {
            severity = SemanticError.Severity.WARNING.name();
        }
    }

    /** True when this finding should block a "perfect query" verdict in the UI. */
    public boolean productionRisk() {
        return SemanticError.Severity.WARNING.name().equalsIgnoreCase(severity);
    }
}
