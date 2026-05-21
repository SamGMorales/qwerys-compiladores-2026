package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 24F — Cassandra BATCH + LWT rule codes (CAS-BATCH-*, CAS-LWT-*).
 */
class CqlAnalyzerBatchAndLwtTest {

    private final CqlAnalyzer analyzer = new CqlAnalyzer();

    private static List<String> codes(List<SemanticError> e) {
        return e.stream().map(SemanticError::code).collect(Collectors.toList());
    }

    @Test
    void casBatch001_moreThanFiftyStatements_error() {
        StringBuilder sb = new StringBuilder("BEGIN BATCH\n");
        for (int i = 0; i < 51; i++) {
            sb.append("INSERT INTO ks.t (id, v) VALUES (").append(i).append(", 1);\n");
        }
        sb.append("APPLY BATCH");
        List<SemanticError> out = analyzer.analyze(sb.toString(), Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-BATCH-001"));
    }

    @Test
    void casBatch002_crossPartition_warningWithSchema() {
        String script = """
                CREATE TABLE ks.events (id int, day int, v text, PRIMARY KEY (id, day));
                BEGIN BATCH
                UPDATE ks.events SET v = 'a' WHERE id = 1 AND day = 1;
                UPDATE ks.events SET v = 'b' WHERE id = 2 AND day = 1;
                APPLY BATCH
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-BATCH-002"));
    }

    @Test
    void casBatch003_unloggedSinglePartition_info() {
        String script = """
                CREATE TABLE ks.events (id int, day int, v text, PRIMARY KEY (id, day));
                BEGIN UNLOGGED BATCH
                UPDATE ks.events SET v = 'a' WHERE id = 1 AND day = 1;
                UPDATE ks.events SET v = 'b' WHERE id = 1 AND day = 2;
                APPLY BATCH
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-BATCH-003"));
    }

    @Test
    void casBatch004_loggedOnlySinglePartition_warning() {
        String script = """
                CREATE TABLE ks.events (id int, day int, v text, PRIMARY KEY (id, day));
                BEGIN BATCH
                UPDATE ks.events SET v = 'a' WHERE id = 1 AND day = 1;
                UPDATE ks.events SET v = 'b' WHERE id = 1 AND day = 2;
                APPLY BATCH
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-BATCH-004"));
    }

    @Test
    void casBatch005_counterBatchWithInsert_error() {
        String script = """
                BEGIN COUNTER BATCH
                INSERT INTO ks.c (id, cnt) VALUES (1, 1);
                APPLY BATCH
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-BATCH-005"));
    }

    @Test
    void casLwt002_multipleLwtInBatch_error() {
        String script = """
                CREATE TABLE ks.t (id int PRIMARY KEY, v text);
                BEGIN BATCH
                INSERT INTO ks.t (id, v) VALUES (1, 'a') IF NOT EXISTS;
                INSERT INTO ks.t (id, v) VALUES (2, 'b') IF NOT EXISTS;
                APPLY BATCH
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-LWT-002"));
    }

    @Test
    void casLwt001_hotPartitionInsert_warning() {
        String script = """
                CREATE TABLE ks.items (id int, name text, PRIMARY KEY (id));
                INSERT INTO ks.items (id, name) VALUES (1, 'x') IF NOT EXISTS;
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-LWT-001"));
        assertTrue(codes(out).contains("CAS-LWT-003"));
    }
}
