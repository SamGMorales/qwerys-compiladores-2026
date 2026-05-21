package com.qwerys.qwerys_backend.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Optional analysis provenance for custom engines and transparency in the UI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisMetadata(
        /** native | ai-custom | native-approximate-custom */
        String source,
        String declaredEngineLabel,
        String referenceBaseEngine,
        /** Narrative comparing reference-base rules vs the declared custom engine (AI-generated). */
        String referenceComparison
) {
}
