package com.qwerys.qwerys_backend.model.ai;

import com.qwerys.qwerys_backend.adapter.DatabaseConfig;

import java.util.List;

/**
 * Native analyzer output for optional AI pedagogical complement (does not replace native analysis).
 */
public record ComplementAnalysisRequest(
        String query,
        String databaseType,
        String locale,
        Boolean nativeIsValid,
        Boolean expertMode,
        String queryType,
        String dialect,
        String customEngineBase,
        DatabaseConfig connection,
        List<AnalysisErrorDto> errors,
        List<AnalysisWarningDto> warnings,
        List<OptimizationDto> optimizations,
        String liveSchemaNote,
        /**
         * {@code STATEMENT} (default) — one fragment; {@code SCRIPT} — whole input / cross-statement view.
         */
        String analysisScope,
        /** Full user input when {@code analysisScope} is SCRIPT (may equal {@link #query()}). */
        String fullScript,
        /** 1-based index of the statement when scope is STATEMENT in a multi-script run. */
        Integer statementIndex,
        /** Total statements in the script when known. */
        Integer statementCount
) {
    public static final String OPTIMIZATION_SECTION_MARKER = "---QWERYS-OPT---";
}
