package com.qwerys.qwerys_backend.migration;

public record MigrationRequest(
        String sourceCode,
        String sourceLanguage,
        String targetLanguage
) {}
