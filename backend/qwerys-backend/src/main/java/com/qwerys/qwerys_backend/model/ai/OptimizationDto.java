package com.qwerys.qwerys_backend.model.ai;

import com.fasterxml.jackson.annotation.JsonAlias;

public record OptimizationDto(
        String ruleId,
        String impact,
        String description,
        @JsonAlias("original") String originalFragment,
        @JsonAlias("optimized") String optimizedFragment
) {
}
