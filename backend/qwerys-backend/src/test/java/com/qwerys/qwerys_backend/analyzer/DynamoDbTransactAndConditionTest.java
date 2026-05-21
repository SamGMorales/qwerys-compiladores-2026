package com.qwerys.qwerys_backend.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import com.qwerys.qwerys_backend.optimization.OptimizationEngine;
import com.qwerys.qwerys_backend.service.QueryAnalysisService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbTransactAndConditionTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void ddbTx001_moreThan100Actions() throws Exception {
        ArrayNode items = OM.createArrayNode();
        for (int i = 0; i < 101; i++) {
            ObjectNode put = OM.createObjectNode();
            put.put("TableName", "T");
            ObjectNode item = OM.createObjectNode();
            item.set("id", OM.createObjectNode().put("S", "k" + i));
            put.set("Item", item);
            ObjectNode ti = OM.createObjectNode();
            ti.set("Put", put);
            items.add(ti);
        }
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", items);
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyzeTransactRequest(OM.writeValueAsString(root));
        assertTrue(out.stream().anyMatch(e -> "DDB-TX-001".equals(e.code())));
    }

    @Test
    void ddbTx002_mixGetAndWrite() throws Exception {
        ObjectNode root = OM.createObjectNode();
        ArrayNode items = OM.createArrayNode();
        ObjectNode getOp = OM.createObjectNode();
        getOp.put("TableName", "T");
        getOp.set("Key", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        items.add(OM.createObjectNode().set("Get", getOp));
        ObjectNode putOp = OM.createObjectNode();
        putOp.put("TableName", "T");
        putOp.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "2")));
        items.add(OM.createObjectNode().set("Put", putOp));
        root.set("TransactItems", items);
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyzeTransactRequest(OM.writeValueAsString(root));
        assertTrue(out.stream().anyMatch(e -> "DDB-TX-002".equals(e.code())));
    }

    @Test
    void ddbTx003_conditionCheckWithoutExpression() throws Exception {
        ObjectNode cc = OM.createObjectNode();
        cc.put("TableName", "T");
        cc.set("Key", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("ConditionCheck", cc)));
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyzeTransactRequest(OM.writeValueAsString(root));
        assertTrue(out.stream().anyMatch(e -> "DDB-TX-003".equals(e.code())));
    }

    @Test
    void ddbTx004_multiTableInfo() throws Exception {
        ObjectNode p1 = OM.createObjectNode();
        p1.put("TableName", "A");
        p1.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        ObjectNode p2 = OM.createObjectNode();
        p2.put("TableName", "B");
        p2.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "2")));
        ObjectNode root = OM.createObjectNode();
        ArrayNode arr = OM.createArrayNode();
        arr.add(OM.createObjectNode().set("Put", p1));
        arr.add(OM.createObjectNode().set("Put", p2));
        root.set("TransactItems", arr);
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyzeTransactRequest(OM.writeValueAsString(root));
        assertTrue(out.stream().anyMatch(e -> "DDB-TX-004".equals(e.code())));
    }

    @Test
    void ddbTx004_suppressedWhenJustified() throws Exception {
        ObjectNode p1 = OM.createObjectNode();
        p1.put("TableName", "A");
        p1.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        ObjectNode p2 = OM.createObjectNode();
        p2.put("TableName", "B");
        p2.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "2")));
        ObjectNode root = OM.createObjectNode();
        root.put("multiTableJustified", true);
        ArrayNode arr = OM.createArrayNode();
        arr.add(OM.createObjectNode().set("Put", p1));
        arr.add(OM.createObjectNode().set("Put", p2));
        root.set("TransactItems", arr);
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyzeTransactRequest(OM.writeValueAsString(root));
        assertTrue(out.stream().noneMatch(e -> "DDB-TX-004".equals(e.code())));
    }

    @Test
    void ddbCond001_updateOnlyFunctionInCondition() {
        String json = """
                {"kind":"CONDITION","expression":"if_not_exists(#a, :v)","expressionAttributeNames":{"#a":"status"},"expressionAttributeValues":{":v":{"S":"x"}}}
                """;
        List<SemanticError> out = new DynamoDbExpressionAnalyzer().analyze(DynamoDbExpressionPayload.parse(json), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-COND-001".equals(e.code())));
    }

    @Test
    void ddbCond002_attributeExistsNeedsPath() {
        String json = """
                {"kind":"CONDITION","expression":"attribute_exists(:v)","expressionAttributeValues":{":v":{"S":"x"}}}
                """;
        List<SemanticError> out = new DynamoDbExpressionAnalyzer().analyze(DynamoDbExpressionPayload.parse(json), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-COND-002".equals(e.code())));
    }

    @Test
    void ddbCond003_sizeOnNumberAttribute() {
        String json = """
                {"kind":"CONDITION","expression":"size(id) > :z","attributeTypes":{"id":"N"},"expressionAttributeValues":{":z":{"N":"0"}}}
                """;
        List<SemanticError> out = new DynamoDbExpressionAnalyzer().analyze(DynamoDbExpressionPayload.parse(json), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-COND-003".equals(e.code())));
    }

    @Test
    void queryAnalysisService_routesTransactJson() throws Exception {
        ObjectNode put = OM.createObjectNode();
        put.put("TableName", "T");
        put.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("Put", put)));
        QueryAnalysisService svc = com.qwerys.qwerys_backend.service.QueryAnalysisServiceTestFixtures.create();
        QueryRequest req = new QueryRequest(OM.writeValueAsString(root), "dynamodb", "dynamodb", null, "en", null, null);
        QueryAnalysisResponse r = svc.analyzeQuery(req);
        assertTrue(r.isValid());
        assertFalse(r.warnings().stream().anyMatch(c -> c.code().startsWith("DDB-TX-00")));
    }
}
