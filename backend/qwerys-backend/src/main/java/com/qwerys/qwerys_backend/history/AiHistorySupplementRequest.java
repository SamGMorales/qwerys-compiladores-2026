package com.qwerys.qwerys_backend.history;

/**
 * Optional AI second-pass snapshot to attach to a history row after complement-analysis.
 * Does not replace native {@code valid}; adds {@code aiAssistedValid} for display/filtering.
 */
public record AiHistorySupplementRequest(
        Boolean aiAssistedValid,
        String aiProvider,
        String analysisLocale,
        /** Merged analysis JSON (native + overlay) for faithful re-display; optional. */
        String effectiveResultJson,
        /** Full AI complement snapshot (pedagogy, reviews, AI optimizations) for history detail. */
        String aiComplementJson,
        Integer optimizationCount,
        Integer warningCount) {
}
