package com.qwerys.qwerys_backend.model.ai;

import java.util.List;

public record ImproveMigrationRequest(
        String originalCode,
        String sourceLanguage,
        String targetLanguage,
        String currentMigration,
        List<String> warnings,
        List<String> manualSteps,
        String locale
) {
}
