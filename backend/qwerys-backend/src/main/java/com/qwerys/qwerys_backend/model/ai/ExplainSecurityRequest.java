package com.qwerys.qwerys_backend.model.ai;

/**
 * Optional AI enrichment for a rule-based security finding (does not replace detection).
 */
public record ExplainSecurityRequest(
        String query,
        String databaseType,
        String patternId,
        String ruleKey,
        String riskSummary,
        String locale
) {
}
