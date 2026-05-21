package com.qwerys.qwerys_backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Educational explanation for student mode (optional API payload).
 * Shape matches the frontend {@code StudentExplanation} interface.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StudentExplanationDto(
        String what,
        String why,
        String example,
        String correctedExample
) {}
