package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.adapter.DatabaseConfig;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional live-schema layer applied <strong>after</strong> engine analyzers when a
 * {@link DatabaseConfig} is present. Does not replace or alter delegate findings.
 */
public final class LiveSchemaEnrichment {

    public enum Engine {
        MONGODB,
        CASSANDRA,
        REDIS,
        ELASTICSEARCH,
        DYNAMODB_PARTIQL,
        DYNAMODB_TRANSACT,
        DYNAMODB_EXPRESSION,
        DYNAMODB_MANAGEMENT
    }

    private LiveSchemaEnrichment() {
    }

    public static List<SemanticError> enrich(
            List<SemanticError> delegateFindings,
            String rawQuery,
            DatabaseConfig connection,
            Locale ui,
            Engine engine) {
        return enrich(delegateFindings, rawQuery, connection, ui, engine, null, null);
    }

    public static List<SemanticError> enrich(
            List<SemanticError> delegateFindings,
            String rawQuery,
            DatabaseConfig connection,
            Locale ui,
            Engine engine,
            AstNode ast) {
        return enrich(delegateFindings, rawQuery, connection, ui, engine, ast, null);
    }

    public static List<SemanticError> enrich(
            List<SemanticError> delegateFindings,
            String rawQuery,
            DatabaseConfig connection,
            Locale ui,
            Engine engine,
            AstNode ast,
            String elasticsearchTargetIndex) {
        if (connection == null) {
            return delegateFindings;
        }
        LiveSchemaValidationResult validation = SchemaValidationSupport.validateForAnalysis(connection, ui);
        if (!validation.isUsable()) {
            return SchemaValidationSupport.appendLiveSchemaFindings(delegateFindings, validation, ui);
        }
        DatabaseSchema schema = validation.schema();
        List<SemanticError> merged = new ArrayList<>(delegateFindings);
        switch (engine) {
            case MONGODB -> MongoLiveSchemaValidator.validate(rawQuery, schema, ui, merged);
            case CASSANDRA -> CqlLiveSchemaValidator.validate(rawQuery, schema, ui, merged);
            case REDIS -> RedisLiveSchemaValidator.validate(rawQuery, schema, ui, merged);
            case ELASTICSEARCH -> ElasticsearchLiveSchemaValidator.validate(
                    rawQuery, elasticsearchTargetIndex, schema, ui, merged);
            case DYNAMODB_PARTIQL -> {
                if (ast != null) {
                    AstLiveSchemaValidator.validate(
                            ast, schema, ui, SchemaEntityLabels.DYNAMODB, merged);
                }
            }
            case DYNAMODB_TRANSACT -> DynamoDbLiveSchemaValidator.validateTransactJson(rawQuery, schema, ui, merged);
            case DYNAMODB_EXPRESSION -> DynamoDbLiveSchemaValidator.validateExpressionPayload(rawQuery, schema, ui, merged);
            case DYNAMODB_MANAGEMENT -> DynamoDbLiveSchemaValidator.validateManagementJson(rawQuery, schema, ui, merged);
        }
        return merged;
    }
}
