package com.qwerys.qwerys_backend.migration;

import java.util.List;

public record MigrationResult(
        boolean success,
        String migratedCode,
        List<String> warnings,
        List<String> manualSteps
) {}
