package com.qwerys.qwerys_backend.model.ai;

/** AI review of a specific native error, warning, or optimization rule. */
public record NativeFindingReviewDto(
        String referenceId,
        /** AGREE | PARTIAL | DISAGREE */
        String verdict,
        String comment
) {
}
