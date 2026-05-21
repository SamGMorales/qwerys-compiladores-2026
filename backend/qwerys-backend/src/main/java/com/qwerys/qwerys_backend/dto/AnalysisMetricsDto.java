package com.qwerys.qwerys_backend.dto;

/**
 * Pipeline timing and structural metrics for expert-mode analysis.
 */
public record AnalysisMetricsDto(
        long lexingTimeMs,
        long parsingTimeMs,
        long semanticTimeMs,
        int totalTokens,
        int astDepth,
        int optimizationRulesEvaluated
) {}
