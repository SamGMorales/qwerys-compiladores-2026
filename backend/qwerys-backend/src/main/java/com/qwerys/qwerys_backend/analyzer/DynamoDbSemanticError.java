package com.qwerys.qwerys_backend.analyzer;

import java.util.Locale;

/**
 * Semantic finding for DynamoDB PartiQL analysis. Composes the same core fields as
 * {@link SemanticError} plus Dynamo-specific context. {@link SemanticError} cannot be
 * subclassed because it is a {@code record}.
 *
 * @param code           Rule identifier (e.g. {@code DDB-JOIN}).
 * @param message        Human-readable description.
 * @param suggestion     Actionable advice.
 * @param severity       ERROR, WARNING, or INFO.
 * @param tableName      Primary table involved, or empty when unknown.
 * @param estimatedCost  Rough read-cost hint: {@code LOW}, {@code MEDIUM}, {@code HIGH},
 *                       {@code VERY_HIGH}, or empty when not applicable.
 */
public record DynamoDbSemanticError(
        String code,
        String message,
        String suggestion,
        SemanticError.Severity severity,
        String tableName,
        String estimatedCost
) {

    /**
     * Builds the {@code suggestion} string passed to API clients ({@link com.qwerys.qwerys_backend.model.AnalysisError}),
     * including table and cost metadata when present.
     */
    public String suggestionForApi() {
        return suggestionForApi(Locale.ENGLISH);
    }

    /**
     * Same as {@link #suggestionForApi()} with localized table/cost suffixes for the UI locale.
     */
    public String suggestionForApi(Locale ui) {
        Locale loc = ui != null ? ui : Locale.ENGLISH;
        boolean es = AnalysisMessages.spanish(loc);
        StringBuilder sb = new StringBuilder();
        if (suggestion != null && !suggestion.isBlank()) {
            sb.append(suggestion.trim());
        }
        if (tableName != null && !tableName.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (es) {
                sb.append("(tabla: ").append(tableName).append(")");
            } else {
                sb.append("(table: ").append(tableName).append(")");
            }
        }
        if (estimatedCost != null && !estimatedCost.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (es) {
                sb.append("[coste de lectura estimado: ").append(estimatedCost).append("]");
            } else {
                sb.append("[estimated read cost: ").append(estimatedCost).append("]");
            }
        }
        return sb.toString();
    }

    /** Adapts to {@link SemanticError} for pipelines that expect that type. */
    public SemanticError toSemanticError() {
        return new SemanticError(code, message, suggestionForApi(), severity, null, null);
    }
}
