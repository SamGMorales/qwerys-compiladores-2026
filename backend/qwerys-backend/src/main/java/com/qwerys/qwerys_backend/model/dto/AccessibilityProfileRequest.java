package com.qwerys.qwerys_backend.model.dto;

import java.util.List;

public record AccessibilityProfileRequest(
        String language,
        Boolean darkTheme,
        Boolean blindMode,
        Boolean lowVisionMode,
        Boolean dyslexiaMode,
        Boolean deafMode,
        Boolean adhdMode,
        Boolean studentMode,
        Boolean expertMode,
        List<String> customDatabases
) {}
