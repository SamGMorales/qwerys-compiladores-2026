package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.List;

/**
 * Result of running the optimization engine, including how many rules were evaluated.
 */
public record OptimizationResult(
        List<OptimizationSuggestion> suggestions,
        int rulesEvaluated
) {}
