package com.qwerys.qwerys_backend.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbStreamsBackupMgmtTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void ddbStr001_streamWithoutTtl() throws Exception {
        ObjectNode ss = OM.createObjectNode();
        ss.put("StreamEnabled", true);
        ss.put("StreamViewType", "NEW_AND_OLD_IMAGES");
        ObjectNode root = OM.createObjectNode();
        root.put("TableName", "T");
        root.set("StreamSpecification", ss);
        List<DynamoDbSemanticError> out =
                new DynamoDbAnalyzer().analyzeManagementPayload(OM.writeValueAsString(root), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-STR-001".equals(e.code())));
    }

    @Test
    void ddbStr001_suppressedWhenTtlEnabled() throws Exception {
        ObjectNode ss = OM.createObjectNode();
        ss.put("StreamEnabled", true);
        ss.put("StreamViewType", "KEYS_ONLY");
        ObjectNode ttl = OM.createObjectNode();
        ttl.put("Enabled", true);
        ObjectNode root = OM.createObjectNode();
        root.put("TableName", "T");
        root.set("StreamSpecification", ss);
        root.set("TimeToLiveSpecification", ttl);
        List<DynamoDbSemanticError> out =
                new DynamoDbAnalyzer().analyzeManagementPayload(OM.writeValueAsString(root), Locale.ENGLISH);
        assertTrue(out.stream().noneMatch(e -> "DDB-STR-001".equals(e.code())));
    }

    @Test
    void ddbStr002_lambdaOnStreamWithoutDlq() throws Exception {
        ObjectNode esm = OM.createObjectNode();
        esm.put("FunctionName", "proc");
        esm.put("EventSourceArn", "arn:aws:dynamodb:eu-west-1:123456789012:table/T/stream/2020-01-01T00:00:00.000");
        ObjectNode root = OM.createObjectNode();
        root.set("EventSourceMapping", esm);
        List<DynamoDbSemanticError> out =
                new DynamoDbAnalyzer().analyzeManagementPayload(OM.writeValueAsString(root), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-STR-002".equals(e.code())));
    }

    @Test
    void ddbStr002_suppressedWithOnFailure() throws Exception {
        ObjectNode esm = OM.createObjectNode();
        esm.put("FunctionName", "proc");
        esm.put("EventSourceArn", "arn:aws:dynamodb:eu-west-1:123456789012:table/T/stream/2020-01-01T00:00:00.000");
        ObjectNode dest = OM.createObjectNode();
        ObjectNode onFail = OM.createObjectNode();
        onFail.put("Destination", "arn:aws:sqs:eu-west-1:123456789012:dlq");
        dest.set("OnFailure", onFail);
        esm.set("DestinationConfig", dest);
        ObjectNode root = OM.createObjectNode();
        root.set("EventSourceMapping", esm);
        List<DynamoDbSemanticError> out =
                new DynamoDbAnalyzer().analyzeManagementPayload(OM.writeValueAsString(root), Locale.ENGLISH);
        assertTrue(out.stream().noneMatch(e -> "DDB-STR-002".equals(e.code())));
    }

    @Test
    void ddbStrView_unknownViewType() throws Exception {
        ObjectNode ss = OM.createObjectNode();
        ss.put("StreamEnabled", true);
        ss.put("StreamViewType", "ALL_IMAGES");
        ObjectNode root = OM.createObjectNode();
        root.set("StreamSpecification", ss);
        List<DynamoDbSemanticError> out =
                new DynamoDbAnalyzer().analyzeManagementPayload(OM.writeValueAsString(root), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-STR-VIEW".equals(e.code())));
    }

    @Test
    void ddbBak001_productionContinuousBackupsOff() throws Exception {
        ObjectNode cbd = OM.createObjectNode();
        cbd.put("ContinuousBackupsStatus", "DISABLED");
        ObjectNode root = OM.createObjectNode();
        root.put("environment", "production");
        root.set("ContinuousBackupsDescription", cbd);
        List<DynamoDbSemanticError> out =
                new DynamoDbAnalyzer().analyzeManagementPayload(OM.writeValueAsString(root), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-BAK-001".equals(e.code())));
    }

    @Test
    void ddbBak002_restoreSameTableNameJson() throws Exception {
        ObjectNode rt = OM.createObjectNode();
        rt.put("TargetTableName", "Orders");
        rt.put("BackupArn", "arn:aws:dynamodb:us-east-1:123456789012:table/Orders/backup/01730138131572-abc");
        ObjectNode root = OM.createObjectNode();
        root.set("restoreTableFromBackup", rt);
        List<DynamoDbSemanticError> out =
                new DynamoDbAnalyzer().analyzeManagementPayload(OM.writeValueAsString(root), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-BAK-002".equals(e.code())
                && e.severity() == SemanticError.Severity.ERROR));
    }

    @Test
    void ddbBak002_plaintextRestore() {
        String line = "RESTORE TABLE Orders FROM BACKUP arn:aws:dynamodb:us-east-1:123456789012:table/Orders/backup/x";
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyzeManagementPayload(line, Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-BAK-002".equals(e.code())));
    }

    @Test
    void ddbBak003_pitrDisabledInDescribe() throws Exception {
        ObjectNode pitr = OM.createObjectNode();
        pitr.put("PointInTimeRecoveryStatus", "DISABLED");
        ObjectNode cbd = OM.createObjectNode();
        cbd.set("PointInTimeRecoveryDescription", pitr);
        ObjectNode root = OM.createObjectNode();
        root.put("TableName", "T");
        root.set("ContinuousBackupsDescription", cbd);
        List<DynamoDbSemanticError> out =
                new DynamoDbAnalyzer().analyzeManagementPayload(OM.writeValueAsString(root), Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-BAK-003".equals(e.code())));
    }

    @Test
    void ddbApi001_selectStarLargeTableHint() throws Exception {
        String sql = "/* QWERYS_TABLE_SIZE_BYTES 2000000 */ SELECT * FROM items";
        SqlLexer lexer = new SqlLexer(sql, SqlDialect.GENERIC);
        SqlParser parser = new SqlParser(lexer.tokenize(), SqlDialect.GENERIC);
        AstNode ast = parser.parse();
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyze(ast, sql, Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-API-001".equals(e.code())));
        assertTrue(out.stream().noneMatch(e -> "DDB-SELECT-STAR".equals(e.code())));
    }

    @Test
    void ddbApi002_updateNoWhere() throws Exception {
        String sql = "UPDATE t SET a = 1";
        SqlLexer lexer = new SqlLexer(sql, SqlDialect.GENERIC);
        SqlParser parser = new SqlParser(lexer.tokenize(), SqlDialect.GENERIC);
        AstNode ast = parser.parse();
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyze(ast, sql, Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-API-002".equals(e.code())));
        assertFalse(out.stream().anyMatch(e -> "DDB-UPDATE-NO-WHERE".equals(e.code())));
    }

    @Test
    void ddbApi002_updateWhereWithoutEquality() throws Exception {
        String sql = "UPDATE t SET a = 1 WHERE b > 3";
        SqlLexer lexer = new SqlLexer(sql, SqlDialect.GENERIC);
        SqlParser parser = new SqlParser(lexer.tokenize(), SqlDialect.GENERIC);
        AstNode ast = parser.parse();
        List<DynamoDbSemanticError> out = new DynamoDbAnalyzer().analyze(ast, sql, Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "DDB-API-002".equals(e.code())));
    }

    @Test
    void looksLikeManagementPayload_recognizesStreamSpec() {
        assertTrue(DynamoDbAnalyzer.looksLikeManagementPayload("{\"StreamSpecification\":{\"StreamEnabled\":true}}"));
    }

    @Test
    void extractTableFromDynamoArn_backup() {
        String arn = "arn:aws:dynamodb:us-east-1:1:table/MyTbl/backup/xyz";
        assertTrue("MyTbl".equalsIgnoreCase(DynamoDbAnalyzer.extractTableFromDynamoArn(arn)));
    }
}
