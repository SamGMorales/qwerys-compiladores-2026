package com.qwerys.qwerys_backend.model.ai;

/** Corrected SQL/NoSQL snippet for a native error code (student + error panels). */
public record SyntaxCorrectionDto(
        String forErrorCode,
        String correctedQuery,
        String explanation
) {
}
