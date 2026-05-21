package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.adapter.DatabaseConfig;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.analyzer.schema.AstLiveSchemaValidator;
import com.qwerys.qwerys_backend.analyzer.schema.LiveSchemaValidationResult;
import com.qwerys.qwerys_backend.analyzer.schema.SchemaEntityLabels;
import com.qwerys.qwerys_backend.analyzer.schema.SchemaValidationSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Decorator over a {@link SemanticAnalyzer} (or dialect subclass) that validates table and column
 * references against a live database schema when a {@link DatabaseConfig} is supplied.
 *
 * <p>If schema introspection fails, schema checks are skipped silently and the delegate analysis
 * result is returned unchanged.
 */
public class SchemaAwareSemanticAnalyzer extends SemanticAnalyzer {

    private final SemanticAnalyzer delegate;
    private final DatabaseConfig connection;
    private final DatabaseSchema prefetchedSchema;

    public SchemaAwareSemanticAnalyzer(SemanticAnalyzer delegate, DatabaseConfig connection) {
        super(delegate.root, delegate.rawSql, delegate.uiLocale);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.prefetchedSchema = null;
    }

    /**
     * Package-private constructor for unit tests with an in-memory schema snapshot.
     */
    SchemaAwareSemanticAnalyzer(SemanticAnalyzer delegate, DatabaseSchema schema) {
        super(delegate.root, delegate.rawSql, delegate.uiLocale);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.connection = null;
        this.prefetchedSchema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public List<SemanticError> analyze() {
        List<SemanticError> findings = new ArrayList<>(delegate.analyze());
        if (prefetchedSchema != null) {
            AstLiveSchemaValidator.validate(root, prefetchedSchema, uiLocale, SchemaEntityLabels.SQL_TABLE, findings);
            return List.copyOf(findings);
        }
        LiveSchemaValidationResult validation = SchemaValidationSupport.validateForAnalysis(connection, uiLocale);
        if (!validation.isUsable()) {
            findings.addAll(SchemaValidationSupport.liveSchemaFindings(validation, uiLocale));
            return List.copyOf(findings);
        }
        AstLiveSchemaValidator.validate(root, validation.schema(), uiLocale, SchemaEntityLabels.SQL_TABLE, findings);
        return List.copyOf(findings);
    }
}
