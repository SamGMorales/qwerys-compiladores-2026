package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoDbAnalyzerWrongEngineTest {

    @Test
    void sqlServerSyntax_emitsMgoWrongEngine() {
        MongoDbAnalyzer analyzer = new MongoDbAnalyzer();
        List<SemanticError> findings = analyzer.analyze(
                "SELECT TOP 10 * FROM usuarios ORDER BY id",
                Locale.forLanguageTag("es"));
        assertTrue(findings.stream().anyMatch(e -> "MGO-WRONG-ENGINE".equals(e.code())));
        assertTrue(findings.stream()
                .filter(e -> "MGO-WRONG-ENGINE".equals(e.code()))
                .anyMatch(e -> e.message().contains("MongoDB")));
    }

    @Test
    void validMongoShell_doesNotEmitWrongEngine() {
        MongoDbAnalyzer analyzer = new MongoDbAnalyzer();
        List<SemanticError> findings = analyzer.analyze(
                "db.usuarios.find({ activo: true })",
                Locale.ENGLISH);
        Set<String> codes = findings.stream().map(SemanticError::code).collect(Collectors.toSet());
        assertFalse(codes.contains("MGO-WRONG-ENGINE"));
    }

    @Test
    void redisKeysSyntax_emitsMgoWrongEngine() {
        MongoDbAnalyzer analyzer = new MongoDbAnalyzer();
        List<SemanticError> findings = analyzer.analyze("KEYS *", Locale.ENGLISH);
        assertTrue(findings.stream().anyMatch(e -> "MGO-WRONG-ENGINE".equals(e.code())));
    }

    @Test
    void unrecognizedNonDbSyntax_doesNotEmitWrongEngine() {
        MongoDbAnalyzer analyzer = new MongoDbAnalyzer();
        List<SemanticError> findings = analyzer.analyze("not a mongo call at all", Locale.ENGLISH);
        assertFalse(findings.stream().anyMatch(e -> "MGO-WRONG-ENGINE".equals(e.code())));
    }

    @Test
    void cassandraBatchSyntax_emitsMgoWrongEngine() {
        MongoDbAnalyzer analyzer = new MongoDbAnalyzer();
        List<SemanticError> findings = analyzer.analyze(
                "BEGIN BATCH\nINSERT INTO users (id, name) VALUES (1, 'a');\nAPPLY BATCH;",
                Locale.ENGLISH);
        assertTrue(findings.stream().anyMatch(e -> "MGO-WRONG-ENGINE".equals(e.code())));
    }

    @Test
    void elasticsearchDsl_emitsMgoWrongEngine() {
        MongoDbAnalyzer analyzer = new MongoDbAnalyzer();
        List<SemanticError> findings = analyzer.analyze(
                "{\"query\":{\"match_all\":{}}}",
                Locale.ENGLISH);
        assertTrue(findings.stream().anyMatch(e -> "MGO-WRONG-ENGINE".equals(e.code())));
    }
}
