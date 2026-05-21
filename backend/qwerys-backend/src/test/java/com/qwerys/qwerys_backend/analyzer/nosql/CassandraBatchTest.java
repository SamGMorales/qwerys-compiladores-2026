package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.CqlAnalyzer;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.StatementSplitter;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cassandra BATCH / LWT / multi-statement CQL coverage (Day 24F+). */
class CassandraBatchTest {

    private final CqlAnalyzer analyzer = new CqlAnalyzer();

    private static Set<String> codes(List<SemanticError> e) {
        return e.stream().map(SemanticError::code).collect(Collectors.toSet());
    }

    @Test
    void simpleSelect_valid() {
        List<SemanticError> r = analyzer.analyze("SELECT id FROM ks.t WHERE id = 1", Locale.ENGLISH);
        assertFalse(codes(r).contains("CAS-BATCH-001"));
    }

    @Test
    void batchOverFifty_emitsBatch001() {
        StringBuilder sb = new StringBuilder("BEGIN BATCH\n");
        for (int i = 0; i < 51; i++) {
            sb.append("INSERT INTO ks.t (id, v) VALUES (").append(i).append(", 1);\n");
        }
        sb.append("APPLY BATCH");
        assertTrue(codes(analyzer.analyze(sb.toString(), Locale.ENGLISH)).contains("CAS-BATCH-001"));
    }

    @Test
    void crossPartitionBatch_emitsBatch002() {
        String script = """
                CREATE TABLE ks.events (id int, day int, v text, PRIMARY KEY (id, day));
                BEGIN BATCH
                UPDATE ks.events SET v = 'a' WHERE id = 1 AND day = 1;
                UPDATE ks.events SET v = 'b' WHERE id = 2 AND day = 1;
                APPLY BATCH
                """;
        assertTrue(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-BATCH-002"));
    }

    @Test
    void unloggedSinglePartition_emitsBatch003() {
        String script = """
                CREATE TABLE ks.events (id int, day int, v text, PRIMARY KEY (id, day));
                BEGIN UNLOGGED BATCH
                UPDATE ks.events SET v = 'a' WHERE id = 1 AND day = 1;
                UPDATE ks.events SET v = 'b' WHERE id = 1 AND day = 2;
                APPLY BATCH
                """;
        assertTrue(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-BATCH-003"));
    }

    @Test
    void loggedSinglePartition_emitsBatch004() {
        String script = """
                CREATE TABLE ks.events (id int, day int, v text, PRIMARY KEY (id, day));
                BEGIN BATCH
                UPDATE ks.events SET v = 'a' WHERE id = 1 AND day = 1;
                UPDATE ks.events SET v = 'b' WHERE id = 1 AND day = 2;
                APPLY BATCH
                """;
        assertTrue(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-BATCH-004"));
    }

    @Test
    void counterBatchWithInsert_emitsBatch005() {
        String script = """
                BEGIN COUNTER BATCH
                INSERT INTO ks.c (id, cnt) VALUES (1, 1);
                APPLY BATCH
                """;
        assertTrue(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-BATCH-005"));
    }

    @Test
    void multipleLwtInBatch_emitsLwt002() {
        String script = """
                CREATE TABLE ks.t (id int PRIMARY KEY, v text);
                BEGIN BATCH
                INSERT INTO ks.t (id, v) VALUES (1, 'a') IF NOT EXISTS;
                INSERT INTO ks.t (id, v) VALUES (2, 'b') IF NOT EXISTS;
                APPLY BATCH
                """;
        assertTrue(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-LWT-002"));
    }

    @Test
    void singleIfNotExists_emitsLwt001and003() {
        String script = """
                CREATE TABLE ks.items (id int, name text, PRIMARY KEY (id));
                INSERT INTO ks.items (id, name) VALUES (1, 'x') IF NOT EXISTS;
                """;
        Set<String> c = codes(analyzer.analyze(script, Locale.ENGLISH));
        assertTrue(c.contains("CAS-LWT-001"));
        assertTrue(c.contains("CAS-LWT-003"));
    }

    @Test
    void multiStatement_createThenBatch_fullScript() {
        String full = """
                CREATE TABLE ks.m (id int, c int, v text, PRIMARY KEY (id, c));
                BEGIN BATCH
                UPDATE ks.m SET v = 'a' WHERE id = 1 AND c = 1;
                UPDATE ks.m SET v = 'b' WHERE id = 1 AND c = 2;
                APPLY BATCH
                """;
        assertTrue(codes(analyzer.analyze(full, Locale.ENGLISH)).contains("CAS-BATCH-004"));
    }

    @Test
    void scriptWideListMutations_emitsCqlList001() {
        List<String> stmts = List.of(
                "UPDATE ks.t SET c += 1 WHERE id = 1",
                "UPDATE ks.t SET c += 1 WHERE id = 1",
                "UPDATE ks.t SET c += 1 WHERE id = 1");
        assertTrue(codes(analyzer.scriptWideSemanticFindings(stmts, Locale.ENGLISH)).contains("CQL-LIST-001"));
    }

    @Test
    void ingestFullScriptAndAnalyzeBatchFragment() {
        String full = """
                CREATE TABLE ks.b (id int, c int, v text, PRIMARY KEY (id, c));
                BEGIN BATCH
                UPDATE ks.b SET v = 'x' WHERE id = 1 AND c = 1;
                UPDATE ks.b SET v = 'y' WHERE id = 1 AND c = 2;
                APPLY BATCH
                """;
        List<String> stmts = StatementSplitter.split(full, SqlDialect.GENERIC);
        String batch = stmts.get(stmts.size() - 1);
        List<SemanticError> r = analyzer.ingestFullScriptAndAnalyzeOne(stmts, batch, Locale.ENGLISH);
        assertTrue(codes(r).contains("CAS-BATCH-004"));
    }

    @Test
    void largeLoggedBatch_samePartition_manyUpdates() {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ks.wide (id int, c int, v text, PRIMARY KEY (id, c));\n");
        sb.append("BEGIN BATCH\n");
        for (int i = 0; i < 30; i++) {
            sb.append("UPDATE ks.wide SET v = '").append(i).append("' WHERE id = 7 AND c = ").append(i).append(";\n");
        }
        sb.append("APPLY BATCH");
        assertTrue(codes(analyzer.analyze(sb.toString(), Locale.ENGLISH)).contains("CAS-BATCH-004"));
    }

    @Test
    void insertSingleRow_valid() {
        List<SemanticError> r = analyzer.analyze("INSERT INTO ks.simple (id, v) VALUES (1, 'a')", Locale.ENGLISH);
        assertFalse(codes(r).contains("CAS-BATCH-001"));
    }

    @Test
    void malformedSelect_emitsSyntax() {
        List<SemanticError> r = analyzer.analyze("SELCT * FROM ks.t", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> e.code().startsWith("CQL")));
    }

    @Test
    void counterOnlyBatch_validShape() {
        String script = """
                CREATE TABLE ks.counters (id int PRIMARY KEY, v counter);
                BEGIN COUNTER BATCH
                UPDATE ks.counters SET v = v + 1 WHERE id = 1;
                APPLY BATCH
                """;
        assertFalse(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-BATCH-005"));
    }

    @Test
    void loggedBatchTwoPartitions_no003() {
        String script = """
                CREATE TABLE ks.p (id int, c int, v text, PRIMARY KEY (id, c));
                BEGIN BATCH
                UPDATE ks.p SET v = 'a' WHERE id = 1 AND c = 1;
                UPDATE ks.p SET v = 'b' WHERE id = 2 AND c = 1;
                APPLY BATCH
                """;
        assertFalse(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-BATCH-003"));
    }

    @Test
    void unloggedCrossPartition_still002() {
        String script = """
                CREATE TABLE ks.p (id int, c int, v text, PRIMARY KEY (id, c));
                BEGIN UNLOGGED BATCH
                UPDATE ks.p SET v = 'a' WHERE id = 1 AND c = 1;
                UPDATE ks.p SET v = 'b' WHERE id = 2 AND c = 1;
                APPLY BATCH
                """;
        assertTrue(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-BATCH-002"));
    }

    @Test
    void batchExactlyFifty_no001() {
        StringBuilder sb = new StringBuilder("BEGIN BATCH\n");
        for (int i = 0; i < 50; i++) {
            sb.append("INSERT INTO ks.t50 (id, v) VALUES (").append(i).append(", 1);\n");
        }
        sb.append("APPLY BATCH");
        assertFalse(codes(analyzer.analyze(sb.toString(), Locale.ENGLISH)).contains("CAS-BATCH-001"));
    }

    @Test
    void lwtUpdateIf_emitsLwtHints() {
        String script = """
                CREATE TABLE ks.u (id int PRIMARY KEY, v int);
                UPDATE ks.u SET v = 2 WHERE id = 1 IF v = 1;
                """;
        Set<String> c = codes(analyzer.analyze(script, Locale.ENGLISH));
        assertTrue(c.contains("CAS-LWT-001") || c.contains("CAS-LWT-003"));
    }

    @Test
    void semicolonInsideStringNotSplit() {
        String full = "SELECT * FROM ks.t WHERE id = 1 AND msg = 'a;b'";
        assertEquals(1, StatementSplitter.split(full, SqlDialect.GENERIC).size());
    }

    @Test
    void deepPrimaryKeyManyClusteringColumns() {
        StringBuilder sb = new StringBuilder("CREATE TABLE ks.deep (");
        sb.append("p int");
        for (int i = 0; i < 8; i++) {
            sb.append(", c").append(i).append(" int");
        }
        sb.append(", v text, PRIMARY KEY (p");
        for (int i = 0; i < 8; i++) {
            sb.append(", c").append(i);
        }
        sb.append("));\nINSERT INTO ks.deep (p");
        for (int i = 0; i < 8; i++) {
            sb.append(", c").append(i);
        }
        sb.append(", v) VALUES (1");
        for (int i = 0; i < 8; i++) {
            sb.append(", ").append(i);
        }
        sb.append(", 'ok')");
        List<SemanticError> r = analyzer.analyze(sb.toString(), Locale.ENGLISH);
        assertFalse(codes(r).contains("CQL-SYN-001"));
    }

    @Test
    void batchWithDeleteAndInsert_samePartition() {
        String script = """
                CREATE TABLE ks.z (id int, c int, v text, PRIMARY KEY (id, c));
                BEGIN BATCH
                DELETE FROM ks.z WHERE id = 9 AND c = 1;
                INSERT INTO ks.z (id, c, v) VALUES (9, 1, 'n');
                APPLY BATCH
                """;
        Set<String> c = codes(analyzer.analyze(script, Locale.ENGLISH));
        assertTrue(c.contains("CAS-BATCH-004") || c.contains("CAS-BATCH-003"));
    }

    @Test
    void grantKeyspace_emitsCasDcl() {
        List<SemanticError> r = analyzer.analyze("GRANT ALL ON KEYSPACE cycling TO coach;", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> e.code().startsWith("CAS-DCL")));
    }

    @Test
    void createFunctionJava_emitsUdf001() {
        String cql = """
                CREATE FUNCTION ks.plus (a int, b int)
                CALLED ON NULL INPUT
                RETURNS int
                LANGUAGE java
                AS 'return a+b;';
                """;
        assertTrue(codes(analyzer.analyze(cql, Locale.ENGLISH)).contains("CAS-UDF-001"));
    }

    @Test
    void createTrigger_emitsTrg() {
        String cql = "CREATE TRIGGER tr1 ON ks.users USING 'com.example.Trig';";
        assertTrue(codes(analyzer.analyze(cql, Locale.ENGLISH)).stream().anyMatch(c -> c.startsWith("CAS-TRG")));
    }

    @Test
    void listAppendSingleStatement_noScriptWide001() {
        List<String> one = List.of("UPDATE ks.t SET lst = lst + [1] WHERE id = 1");
        assertFalse(codes(analyzer.scriptWideSemanticFindings(one, Locale.ENGLISH)).contains("CQL-LIST-001"));
    }

    @Test
    void batchWithIfExistsSingle_emitsNoLwt002() {
        String script = """
                CREATE TABLE ks.ie (id int PRIMARY KEY, v text);
                BEGIN BATCH
                INSERT INTO ks.ie (id, v) VALUES (1, 'a') IF NOT EXISTS;
                APPLY BATCH
                """;
        assertFalse(codes(analyzer.analyze(script, Locale.ENGLISH)).contains("CAS-LWT-002"));
    }

    @Test
    void semicolonSeparatedTwoSelects_multiScript() {
        String raw = "SELECT * FROM ks.a WHERE id = 1; SELECT * FROM ks.b WHERE id = 2";
        assertEquals(2, StatementSplitter.split(raw, SqlDialect.GENERIC).size());
    }
}
