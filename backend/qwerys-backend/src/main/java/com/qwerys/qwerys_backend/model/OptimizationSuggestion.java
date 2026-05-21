package com.qwerys.qwerys_backend.model;

/**
 * Represents a single optimization suggestion for a query.
 *
 * @param ruleId            Identifier of the rule that triggered this suggestion (e.g. "NO_SELECT_STAR")
 * @param description       Human-readable explanation of the suggestion
 * @param originalFragment  The problematic fragment found in the original query
 * @param optimizedFragment The suggested replacement fragment
 * @param impact            Estimated performance impact: HIGH, MEDIUM, or LOW
 */
public record OptimizationSuggestion(
        String ruleId,
        String description,
        String originalFragment,
        String optimizedFragment,
        String impact
) {}
