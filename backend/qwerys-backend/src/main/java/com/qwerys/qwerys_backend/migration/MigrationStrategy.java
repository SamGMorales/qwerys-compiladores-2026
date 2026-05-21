package com.qwerys.qwerys_backend.migration;

/**
 * Strategy for a single source→target language pair. Register new pairs with {@code @Component};
 * {@link MigrationService} discovers implementations automatically.
 */
public interface MigrationStrategy {

    /** Uppercase language id, e.g. {@code CPP}, {@code JAVA}, {@code PYTHON}, {@code TYPESCRIPT}. */
    String getSourceLanguage();

    String getTargetLanguage();

    MigrationResult migrate(String sourceCode);
}
