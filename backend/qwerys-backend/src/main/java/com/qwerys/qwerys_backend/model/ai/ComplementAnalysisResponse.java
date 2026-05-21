package com.qwerys.qwerys_backend.model.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComplementAnalysisResponse(
        boolean success,
        String pedagogy,
        String optimizationNotes,
        ValidityCorrectionDto validityCorrection,
        List<NativeFindingReviewDto> nativeReviews,
        List<AnalysisErrorDto> additionalErrors,
        List<AnalysisWarningDto> additionalWarnings,
        List<OptimizationDto> additionalOptimizations,
        List<SyntaxCorrectionDto> syntaxCorrections,
        AiSecondPassOverlayDto secondPassOverlay,
        boolean aiAvailable,
        String provider,
        Long responseTimeMs,
        String error
) {
    public static ComplementAnalysisResponse ok(
            String pedagogy,
            String optimizationNotes,
            ValidityCorrectionDto validityCorrection,
            List<NativeFindingReviewDto> nativeReviews,
            List<AnalysisErrorDto> additionalErrors,
            List<AnalysisWarningDto> additionalWarnings,
            List<OptimizationDto> additionalOptimizations,
            List<SyntaxCorrectionDto> syntaxCorrections,
            AiSecondPassOverlayDto secondPassOverlay,
            boolean aiAvailable,
            String provider,
            long ms) {
        return new ComplementAnalysisResponse(
                true,
                pedagogy,
                optimizationNotes,
                validityCorrection,
                nativeReviews,
                additionalErrors,
                additionalWarnings,
                additionalOptimizations,
                syntaxCorrections,
                secondPassOverlay,
                aiAvailable,
                provider,
                ms,
                null);
    }

    public static ComplementAnalysisResponse fail(String error, boolean aiAvailable, String provider, long ms) {
        return new ComplementAnalysisResponse(
                false,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                aiAvailable,
                provider,
                ms,
                error);
    }
}
