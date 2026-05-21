package com.qwerys.qwerys_backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qwerys.qwerys_backend.dto.AnalysisMetricsDto;
import com.qwerys.qwerys_backend.dto.AstNodeDto;

import java.util.List;

/**
 * Response returned after analyzing a query.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueryAnalysisResponse(
        boolean isValid,
        List<AnalysisError> errors,
        List<AnalysisWarning> warnings,
        List<OptimizationSuggestion> optimizations,
        String analyzedQuery,
        long executionTimeMs,
        AstNodeDto astTree,
        AnalysisMetricsDto metrics,
        AnalysisMetadata metadata,
        /** Set when the analysis was persisted to query history for the signed-in user. */
        Long historyEntryId
) {
    /** Backward-compatible constructor without expert-mode or metadata fields. */
    public QueryAnalysisResponse(
            boolean isValid,
            List<AnalysisError> errors,
            List<AnalysisWarning> warnings,
            List<OptimizationSuggestion> optimizations,
            String analyzedQuery,
            long executionTimeMs) {
        this(isValid, errors, warnings, optimizations, analyzedQuery, executionTimeMs, null, null, null, null);
    }

    /** Expert-mode fields without metadata. */
    public QueryAnalysisResponse(
            boolean isValid,
            List<AnalysisError> errors,
            List<AnalysisWarning> warnings,
            List<OptimizationSuggestion> optimizations,
            String analyzedQuery,
            long executionTimeMs,
            AstNodeDto astTree,
            AnalysisMetricsDto metrics) {
        this(isValid, errors, warnings, optimizations, analyzedQuery, executionTimeMs, astTree, metrics, null, null);
    }

    /** Standard analysis with optional custom-engine metadata (no AST/metrics). */
    public QueryAnalysisResponse(
            boolean isValid,
            List<AnalysisError> errors,
            List<AnalysisWarning> warnings,
            List<OptimizationSuggestion> optimizations,
            String analyzedQuery,
            long executionTimeMs,
            AnalysisMetadata metadata) {
        this(isValid, errors, warnings, optimizations, analyzedQuery, executionTimeMs, null, null, metadata, null);
    }

    public QueryAnalysisResponse withHistoryEntryId(Long historyEntryId) {
        return new QueryAnalysisResponse(
                isValid,
                errors,
                warnings,
                optimizations,
                analyzedQuery,
                executionTimeMs,
                astTree,
                metrics,
                metadata,
                historyEntryId);
    }
}
