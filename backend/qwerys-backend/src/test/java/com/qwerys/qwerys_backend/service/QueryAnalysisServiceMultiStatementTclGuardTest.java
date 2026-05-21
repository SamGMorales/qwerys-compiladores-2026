package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.MultiStatementAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import com.qwerys.qwerys_backend.optimization.OptimizationEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures SQL transaction cross-statement rules (TCL-*) are not applied to NoSQL multi-statement
 * fragments (avoids false positives such as CQL {@code BEGIN BATCH} parsed as SQL {@code BEGIN}).
 */
class QueryAnalysisServiceMultiStatementTclGuardTest {

    private final QueryAnalysisService service = QueryAnalysisServiceTestFixtures.create();

    @Test
    void cassandra_multiStatement_batch_doesNotInjectTcl001IntoStatementWarnings() {
        String script = """
                CREATE TABLE demo.kv (id int, v text, PRIMARY KEY (id));
                BEGIN BATCH
                INSERT INTO demo.kv (id, v) VALUES (1, 'a') IF NOT EXISTS;
                INSERT INTO demo.kv (id, v) VALUES (2, 'b') IF NOT EXISTS;
                APPLY BATCH;
                """;
        QueryRequest request = new QueryRequest(script, "cassandra", "cassandra", null, "en", null, null);
        MultiStatementAnalysisResponse multi = service.analyzeMultiStatement(request);
        for (QueryAnalysisResponse r : multi.statements()) {
            assertFalse(
                    r.warnings() != null && r.warnings().stream().anyMatch(w -> "TCL-001".equals(w.code())),
                    "Cassandra fragments must not receive SQL TCL-001 on per-statement warnings");
        }
        assertTrue(
                multi.statements().stream().anyMatch(r -> r.errors().stream().anyMatch(e -> "CAS-LWT-002".equals(e.code()))),
                "Expected CAS-LWT-002 for two LWTs in one batch");
    }

    @Test
    void redis_multiStatement_doesNotInjectTcl001IntoStatementWarnings() {
        QueryRequest request = new QueryRequest("SET a 1;\nSET b 2;", "redis", "redis", null, "en", null, null);
        MultiStatementAnalysisResponse multi = service.analyzeMultiStatement(request);
        for (QueryAnalysisResponse r : multi.statements()) {
            assertFalse(
                    r.warnings() != null && r.warnings().stream().anyMatch(w -> "TCL-001".equals(w.code())),
                    "Redis fragments must not receive SQL TCL-001");
        }
    }
}
