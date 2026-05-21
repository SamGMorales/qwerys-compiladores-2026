package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.DynamoDbExpressionAnalyzer;
import com.qwerys.qwerys_backend.analyzer.DynamoDbExpressionPayload;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import com.qwerys.qwerys_backend.optimization.OptimizationEngine;
import com.qwerys.qwerys_backend.service.QueryAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Common DynamoDB SDK expression shapes should parse and not fail with lexer/syntax false negatives.
 */
class DynamoDbSdkExpressionBatteryTest {

    private final DynamoDbExpressionAnalyzer exprAnalyzer = new DynamoDbExpressionAnalyzer();
    private final QueryAnalysisService queryService = com.qwerys.qwerys_backend.service.QueryAnalysisServiceTestFixtures.create();

    private static Set<String> codes(List<SemanticError> findings) {
        return findings.stream().map(SemanticError::code).collect(Collectors.toSet());
    }

    private static boolean hasSyntaxBlocker(Set<String> c) {
        return c.contains("DDB-EXPR-LEX") || c.contains("DDB-EXPR-PARSE") || c.contains("DDB-EXPR-EMPTY");
    }

    @Test
    void updateExpression_setPlainText() {
        String expr = "SET #status = :active, #count = #count + :inc";
        List<SemanticError> r = exprAnalyzer.analyze(DynamoDbExpressionPayload.parse(expr), Locale.ENGLISH);
        assertFalse(hasSyntaxBlocker(codes(r)));
    }

    @Test
    void conditionExpression_attributeExists() {
        String json = """
                {"kind":"CONDITION","expression":"attribute_exists(#pk) AND #sk = :skVal",
                 "expressionAttributeNames":{"#pk":"userId","#sk":"sortKey"},
                 "expressionAttributeValues":{":skVal":{"S":"2024"}}}
                """;
        List<SemanticError> r = exprAnalyzer.analyze(DynamoDbExpressionPayload.parse(json), Locale.ENGLISH);
        assertFalse(hasSyntaxBlocker(codes(r)));
    }

    @Test
    void filterExpression_beginsWith() {
        String json = """
                {"kind":"FILTER","expression":"begins_with(#name, :prefix)",
                 "expressionAttributeNames":{"#name":"displayName"},
                 "expressionAttributeValues":{":prefix":{"S":"Acme"}}}
                """;
        List<SemanticError> r = exprAnalyzer.analyze(DynamoDbExpressionPayload.parse(json), Locale.ENGLISH);
        assertFalse(hasSyntaxBlocker(codes(r)));
    }

    @Test
    void keyCondition_equality() {
        String json = """
                {"kind":"KEY_CONDITION","expression":"#pk = :id",
                 "partitionKeyAttributeName":"pk",
                 "expressionAttributeNames":{"#pk":"pk"},
                 "expressionAttributeValues":{":id":{"S":"user-42"}}}
                """;
        List<SemanticError> r = exprAnalyzer.analyze(DynamoDbExpressionPayload.parse(json), Locale.ENGLISH);
        assertFalse(hasSyntaxBlocker(codes(r)));
    }

    @Test
    void queryService_routesSdkUpdateExpression() {
        String sdk = "SET #n = :v REMOVE #old";
        QueryRequest req = new QueryRequest(sdk, "dynamodb", "dynamodb", null, "en", null, null);
        QueryAnalysisResponse r = queryService.analyzeQuery(req);
        assertTrue(r.isValid());
        assertFalse(r.errors().stream().anyMatch(e -> e.code().startsWith("DDB-EXPR-")));
        assertFalse(r.errors().stream().anyMatch(e -> "SYN-001".equals(e.code())));
    }

    @Test
    void queryService_partiQlSelect_notSdk() {
        String partiql = "SELECT id, name FROM Users WHERE id = '1'";
        QueryRequest req = new QueryRequest(partiql, "dynamodb", "dynamodb", null, "en", null, null);
        QueryAnalysisResponse r = queryService.analyzeQuery(req);
        assertFalse(r.errors().stream().anyMatch(e -> e.code().startsWith("DDB-EXPR-")));
    }
}
