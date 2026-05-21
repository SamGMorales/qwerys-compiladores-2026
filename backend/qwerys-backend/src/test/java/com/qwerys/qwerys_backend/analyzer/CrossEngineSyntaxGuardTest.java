package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import com.qwerys.qwerys_backend.optimization.OptimizationEngine;
import com.qwerys.qwerys_backend.service.QueryAnalysisService;
import com.qwerys.qwerys_backend.service.QueryAnalysisServiceTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossEngineSyntaxGuardTest {

    private final QueryAnalysisService service = QueryAnalysisServiceTestFixtures.create();

    @ParameterizedTest
    @CsvSource({
            "MONGODB, SELECT TOP 10 * FROM users, MGO-WRONG-ENGINE",
            "REDIS, SELECT * FROM users, RDS-WRONG-ENGINE",
            "REDIS, db.users.find({}), RDS-WRONG-ENGINE",
            "CASSANDRA, db.users.find({}), CQL-WRONG-ENGINE",
            "CASSANDRA, KEYS *, CQL-WRONG-ENGINE",
            "ELASTICSEARCH, SELECT * FROM users, ES-WRONG-ENGINE",
            "ELASTICSEARCH, KEYS *, ES-WRONG-ENGINE",
            "mysql, db.users.find({}), SQL-WRONG-ENGINE",
            "mysql, KEYS *, SQL-WRONG-ENGINE",
            "oracle, GET index/_search, SQL-WRONG-ENGINE"
    })
    void wrongInputOnEngine_emitsWrongEngineCode(String databaseType, String query, String expectedCode) {
        QueryAnalysisResponse r = service.analyzeQuery(new QueryRequest(
                query, databaseType, databaseType, null, "en", null, null));
        assertFalse(r.isValid());
        assertTrue(r.errors().stream().anyMatch(e -> expectedCode.equals(e.code())),
                () -> "Expected " + expectedCode + " but got " + r.errors());
    }

    @ParameterizedTest
    @CsvSource({
            "MONGODB, db.users.find({ activo: true })",
            "REDIS, SET mykey \"value\"",
            "CASSANDRA, SELECT id FROM ks.users WHERE pk = 1",
            "ELASTICSEARCH, GET users/_search",
            "mysql, SELECT * FROM users",
            "dynamodb, SELECT * FROM \"users\""
    })
    void validShapeForEngine_passesGuard(String databaseType, String query) {
        QueryAnalysisResponse r = service.analyzeQuery(new QueryRequest(
                query, databaseType, databaseType, null, "en", null, null));
        assertFalse(r.errors().stream().anyMatch(e -> e.code().endsWith("-WRONG-ENGINE")),
                () -> "Unexpected WRONG-ENGINE: " + r.errors());
    }

    @Test
    void unknownDatabaseType_isInvalidNotSilentOk() {
        QueryAnalysisResponse r = service.analyzeQuery(new QueryRequest(
                "SELECT 1", "neo4j", "neo4j", null, "en", null, null));
        assertFalse(r.isValid());
        assertEquals("UNKNOWN-ENGINE", r.errors().get(0).code());
    }

    @Test
    void customDatabaseType_embeddedBaseRoutesLikePostgreSql() {
        QueryAnalysisResponse r = service.analyzeQuery(new QueryRequest(
                "SELECT * FROM t",
                "custom::CorpPg::postgresql",
                "postgresql",
                null,
                "en",
                null,
                null));
        assertFalse(r.errors().stream().anyMatch(e -> "UNKNOWN-ENGINE".equals(e.code())));
    }

    @Test
    void customDatabaseType_explicitCustomEngineBase_overridesMissingEmbeddedBase() {
        QueryAnalysisResponse r = service.analyzeQuery(new QueryRequest(
                "GET users/_search",
                "custom::MyEsAlias",
                "elasticsearch",
                null,
                "en",
                null,
                "elasticsearch"));
        assertFalse(r.errors().stream().anyMatch(e -> "UNKNOWN-ENGINE".equals(e.code())));
        assertFalse(r.errors().stream().anyMatch(e -> e.code().endsWith("-WRONG-ENGINE")));
    }

    @Test
    void guardUnit_mongoDetectsSql() {
        Optional<SemanticError> err = CrossEngineSyntaxGuard.check(
                "SELECT * FROM t", CrossEngineSyntaxGuard.TargetEngine.MONGODB, Locale.ENGLISH);
        assertTrue(err.isPresent());
        assertEquals("MGO-WRONG-ENGINE", err.get().code());
    }

    @Test
    void guardUnit_cassandraAllowsSelect() {
        Optional<SemanticError> err = CrossEngineSyntaxGuard.check(
                "SELECT id FROM ks.t WHERE pk = 1",
                CrossEngineSyntaxGuard.TargetEngine.CASSANDRA,
                Locale.ENGLISH);
        assertTrue(err.isEmpty());
    }
}
