package com.qwerys.qwerys_backend.model.ai;

/**
 * When the native analyzer is wrong about validity (false invalid/valid), the AI may request a UI override.
 */
public record ValidityCorrectionDto(
        boolean apply,
        boolean correctedIsValid,
        String reason
) {
}
