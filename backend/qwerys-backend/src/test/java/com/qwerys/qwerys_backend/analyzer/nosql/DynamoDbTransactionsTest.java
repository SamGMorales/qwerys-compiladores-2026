package com.qwerys.qwerys_backend.analyzer.nosql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qwerys.qwerys_backend.analyzer.DynamoDbAnalyzer;
import com.qwerys.qwerys_backend.analyzer.DynamoDbSemanticError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DynamoDB {@code TransactItems} JSON and condition-expression integration (Day 24I+). */
class DynamoDbTransactionsTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private final DynamoDbAnalyzer analyzer = new DynamoDbAnalyzer();

    private static Set<String> codes(List<DynamoDbSemanticError> e) {
        return e.stream().map(DynamoDbSemanticError::code).collect(Collectors.toSet());
    }

    private static ObjectNode putItem(String table, String id) {
        ObjectNode put = OM.createObjectNode();
        put.put("TableName", table);
        put.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", id)));
        return OM.createObjectNode().set("Put", put);
    }

    @Test
    void validSinglePut_noTx001() throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(putItem("T", "1")));
        assertFalse(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-001"));
    }

    @Test
    void tx001_moreThan100Actions() throws Exception {
        ArrayNode items = OM.createArrayNode();
        for (int i = 0; i < 101; i++) {
            items.add(putItem("T", "k" + i));
        }
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", items);
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-001"));
    }

    @Test
    void tx002_mixGetAndWrite() throws Exception {
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
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-002"));
    }

    @Test
    void tx003_conditionCheckWithoutExpression() throws Exception {
        ObjectNode cc = OM.createObjectNode();
        cc.put("TableName", "T");
        cc.set("Key", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("ConditionCheck", cc)));
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-003"));
    }

    @Test
    void tx004_multiTable() throws Exception {
        ObjectNode root = OM.createObjectNode();
        ArrayNode arr = OM.createArrayNode();
        arr.add(putItem("A", "1"));
        arr.add(putItem("B", "2"));
        root.set("TransactItems", arr);
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-004"));
    }

    @Test
    void tx004_suppressedWhenJustified() throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.put("multiTableJustified", true);
        ArrayNode arr = OM.createArrayNode();
        arr.add(putItem("A", "1"));
        arr.add(putItem("B", "2"));
        root.set("TransactItems", arr);
        assertFalse(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-004"));
    }

    @Test
    void txJson_invalid_emitsTxJson() {
        assertTrue(codes(analyzer.analyzeTransactRequest("{TransactItems:[]")).contains("DDB-TX-JSON"));
    }

    @Test
    void notObjectRoot_returnsEmpty() {
        assertTrue(analyzer.analyzeTransactRequest("[]").isEmpty());
    }

    @Test
    void blankInput_returnsEmpty() {
        assertTrue(analyzer.analyzeTransactRequest("   ").isEmpty());
    }

    @Test
    void transactItemsMissing_returnsEmpty() throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.put("Other", 1);
        assertTrue(analyzer.analyzeTransactRequest(OM.writeValueAsString(root)).isEmpty());
    }

    @Test
    void exactly100Puts_noTx001() throws Exception {
        ArrayNode items = OM.createArrayNode();
        for (int i = 0; i < 100; i++) {
            items.add(putItem("T", "id" + i));
        }
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", items);
        assertFalse(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-001"));
    }

    @Test
    void transactGetItemsOnly_noTx002() throws Exception {
        ArrayNode items = OM.createArrayNode();
        for (int i = 0; i < 3; i++) {
            ObjectNode getOp = OM.createObjectNode();
            getOp.put("TableName", "T");
            getOp.set("Key", OM.createObjectNode().set("id", OM.createObjectNode().put("S", String.valueOf(i))));
            items.add(OM.createObjectNode().set("Get", getOp));
        }
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", items);
        assertFalse(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-002"));
    }

    @Test
    void putCondition_ifNotExistsFunction_emitsCond001() throws Exception {
        ObjectNode put = OM.createObjectNode();
        put.put("TableName", "T");
        put.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        put.put("ConditionExpression", "if_not_exists(#a, :v)");
        put.set("ExpressionAttributeNames", OM.createObjectNode().put("#a", "status"));
        put.set("ExpressionAttributeValues", OM.createObjectNode().set(":v", OM.createObjectNode().put("S", "x")));
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("Put", put)));
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-COND-001"));
    }

    @Test
    void putCondition_attributeExistsOnValue_emitsCond002() throws Exception {
        ObjectNode put = OM.createObjectNode();
        put.put("TableName", "T");
        put.set("Item", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        put.put("ConditionExpression", "attribute_exists(:v)");
        put.set("ExpressionAttributeValues", OM.createObjectNode().set(":v", OM.createObjectNode().put("S", "x")));
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("Put", put)));
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-COND-002"));
    }

    @Test
    void putCondition_sizeOnNumber_emitsCond003() throws Exception {
        ObjectNode put = OM.createObjectNode();
        put.put("TableName", "T");
        ObjectNode item = OM.createObjectNode();
        item.set("id", OM.createObjectNode().put("N", "42"));
        put.set("Item", item);
        put.put("ConditionExpression", "size(id) > :z");
        put.set("ExpressionAttributeValues", OM.createObjectNode().set(":z", OM.createObjectNode().put("N", "0")));
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("Put", put)));
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-COND-003"));
    }

    @Test
    void spanishLocale_analyzes() throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(putItem("T", "1")));
        List<DynamoDbSemanticError> out =
                analyzer.analyzeTransactRequest(OM.writeValueAsString(root), new Locale("es", "ES"));
        assertTrue(out.isEmpty());
    }

    @Test
    void updateWithConditionExpression() throws Exception {
        ObjectNode upd = OM.createObjectNode();
        upd.put("TableName", "T");
        upd.set("Key", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        upd.put("UpdateExpression", "SET #n = :one");
        upd.put("ConditionExpression", "#n = :zero");
        upd.set("ExpressionAttributeNames", OM.createObjectNode().put("#n", "version"));
        ObjectNode vals = OM.createObjectNode();
        vals.set(":one", OM.createObjectNode().put("N", "1"));
        vals.set(":zero", OM.createObjectNode().put("N", "0"));
        upd.set("ExpressionAttributeValues", vals);
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("Update", upd)));
        List<DynamoDbSemanticError> out = analyzer.analyzeTransactRequest(OM.writeValueAsString(root));
        assertFalse(out.stream().anyMatch(e -> "DDB-TX-003".equals(e.code())));
    }

    @Test
    void deleteWithCondition() throws Exception {
        ObjectNode del = OM.createObjectNode();
        del.put("TableName", "T");
        del.set("Key", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "9")));
        del.put("ConditionExpression", "attribute_exists(id)");
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("Delete", del)));
        assertFalse(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-003"));
    }

    @Test
    void conditionCheckWithExpression_noTx003() throws Exception {
        ObjectNode cc = OM.createObjectNode();
        cc.put("TableName", "T");
        cc.set("Key", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        cc.put("ConditionExpression", "attribute_exists(id)");
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("ConditionCheck", cc)));
        assertFalse(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-003"));
    }

    @Test
    void multiStatement_twoTransactJson_analyzedIndependently() throws Exception {
        ObjectNode a = OM.createObjectNode();
        a.set("TransactItems", OM.createArrayNode().add(putItem("T", "1")));
        ObjectNode b = OM.createObjectNode();
        b.set("TransactItems", OM.createArrayNode().add(putItem("T", "2")));
        String combined = OM.writeValueAsString(a) + "\n" + OM.writeValueAsString(b);
        String[] lines = combined.split("\n");
        assertEquals(0, codes(analyzer.analyzeTransactRequest(lines[0])).size());
        assertEquals(0, codes(analyzer.analyzeTransactRequest(lines[1])).size());
    }

    @Test
    void threeTables_emitsTx004() throws Exception {
        ArrayNode items = OM.createArrayNode();
        items.add(putItem("T1", "1"));
        items.add(putItem("T2", "2"));
        items.add(putItem("T3", "3"));
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", items);
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-004"));
    }

    @Test
    void largeJsonPayload_manySmallPuts() throws Exception {
        StringBuilder sb = new StringBuilder("{\"TransactItems\":[");
        for (int i = 0; i < 40; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"Put\":{\"TableName\":\"T\",\"Item\":{\"id\":{\"S\":\"k")
                    .append(i)
                    .append("\"}}}}");
        }
        sb.append("]}");
        assertFalse(codes(analyzer.analyzeTransactRequest(sb.toString())).contains("DDB-TX-001"));
    }

    @Test
    void nestedJsonFormatting_parses() throws Exception {
        String raw = """
                {
                  "TransactItems": [
                    { "Put": { "TableName": "T", "Item": { "id": { "S": "x" } } } }
                  ]
                }
                """;
        assertTrue(analyzer.analyzeTransactRequest(raw).isEmpty());
    }

    @Test
    void transactItemsEmptyArray() throws Exception {
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode());
        assertTrue(analyzer.analyzeTransactRequest(OM.writeValueAsString(root)).isEmpty());
    }

    @Test
    void conditionCheckWithBlankExpression_stillTx003() throws Exception {
        ObjectNode cc = OM.createObjectNode();
        cc.put("TableName", "T");
        cc.set("Key", OM.createObjectNode().set("id", OM.createObjectNode().put("S", "1")));
        cc.put("ConditionExpression", "   ");
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", OM.createArrayNode().add(OM.createObjectNode().set("ConditionCheck", cc)));
        assertTrue(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-003"));
    }

    @Test
    void duplicateTableNames_singleTable_noTx004() throws Exception {
        ArrayNode items = OM.createArrayNode();
        items.add(putItem("T", "1"));
        items.add(putItem("T", "2"));
        ObjectNode root = OM.createObjectNode();
        root.set("TransactItems", items);
        assertFalse(codes(analyzer.analyzeTransactRequest(OM.writeValueAsString(root))).contains("DDB-TX-004"));
    }
}
