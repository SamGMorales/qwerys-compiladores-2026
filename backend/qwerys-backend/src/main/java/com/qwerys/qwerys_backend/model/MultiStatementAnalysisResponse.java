package com.qwerys.qwerys_backend.model;

import java.util.List;

/**
 * Response returned after analyzing a multi-statement input (SQL or NoSQL).
 *
 * <p>Each element in {@code statements} corresponds to one individual statement
 * (split by semicolon) with its own full analysis result.
 *
 * @param statements            Individual analysis results, one per statement found
 * @param totalExecutionTimeMs  Wall-clock time for the entire multi-statement analysis (ms)
 * @param scriptLevel           Whole-script view: cross-statement findings + aggregate health context
 * @param scriptHealthPercent   0–100 holistic score (statements + script-level penalties)
 * @param historyEntryId        Optional persisted history row for the full script (authenticated users)
 */
public record MultiStatementAnalysisResponse(
        List<QueryAnalysisResponse> statements,
        long totalExecutionTimeMs,
        QueryAnalysisResponse scriptLevel,
        int scriptHealthPercent,
        Long historyEntryId
) {}
