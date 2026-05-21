package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.AnalysisError;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptLevelSummaryBuilderTest {

    @Test
    void healthPercent_penalizesCrossScriptWarnings() {
        List<QueryAnalysisResponse> stmts = List.of(
                ok("a"),
                ok("b"));
        int h = ScriptLevelSummaryBuilder.computeHealthPercent(stmts, 0, 2);
        assertTrue(h < 100);
    }

    @Test
    void build_addsScrMultiWhenSeveralBlockingStatements() {
        List<QueryAnalysisResponse> stmts = List.of(
                invalid("x"),
                invalid("y"));
        ScriptLevelSummaryBuilder.BuiltScriptLevel b = ScriptLevelSummaryBuilder.build(
                "x;y", "mysql", List.of("x", "y"), stmts, Locale.ENGLISH, List.of());
        assertTrue(b.summary().warnings().stream().anyMatch(w -> "SCR-MULTI-001".equals(w.code())));
    }

    @Test
    void build_scriptInvalidWhenCrossErrorsPresent() {
        List<QueryAnalysisResponse> stmts = List.of(ok("db.a.find({})"));
        var crossErr = new com.qwerys.qwerys_backend.analyzer.SemanticError(
                "MGO-TX-001",
                "msg",
                "sug",
                com.qwerys.qwerys_backend.analyzer.SemanticError.Severity.ERROR,
                1,
                null);
        ScriptLevelSummaryBuilder.BuiltScriptLevel b = ScriptLevelSummaryBuilder.build(
                "session.startTransaction();", "mongodb", List.of("s"), stmts, Locale.ENGLISH, List.of(crossErr));
        assertFalse(b.summary().isValid());
        assertEquals(1, b.summary().errors().size());
        assertEquals("MGO-TX-001", b.summary().errors().get(0).code());
    }

    private static QueryAnalysisResponse ok(String q) {
        return new QueryAnalysisResponse(true, List.of(), List.of(), List.of(), q, 0);
    }

    private static QueryAnalysisResponse invalid(String q) {
        return new QueryAnalysisResponse(
                false,
                List.of(new AnalysisError("E", "m", "s")),
                List.of(),
                List.of(),
                q,
                0);
    }
}
