package com.qwerys.qwerys_backend.model.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qwerys.qwerys_backend.dto.AnalysisMetricsDto;
import com.qwerys.qwerys_backend.dto.AstNodeDto;

/**
 * Native re-parse after AI second pass confirms validity (expert AST/metrics for UI).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiSecondPassOverlayDto(
        boolean suppressNativeErrors,
        boolean reparseSucceeded,
        boolean reparseIsValid,
        AstNodeDto astTree,
        AnalysisMetricsDto metrics
) {
}
