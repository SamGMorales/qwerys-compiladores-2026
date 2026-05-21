package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.SemanticError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PainlessAnalyzerTest {

    @Test
    void pnl001DetectsWhileTrueWithoutBreak() {
        String src = "while (true) { doc['x'].value; }";
        var ctx = new PainlessAnalyzer.PainlessScriptContext(false, false, false, Set.of());
        List<SemanticError> r = PainlessAnalyzer.analyze(src, ctx, Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "PNL-001".equals(e.code())));
    }

    @Test
    void pnl002UnknownDocFieldWhenSchemaProvided() {
        String src = "return doc['missing'].value;";
        var ctx = new PainlessAnalyzer.PainlessScriptContext(false, false, false, Set.of("id"));
        List<SemanticError> r = PainlessAnalyzer.analyze(src, ctx, Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "PNL-002".equals(e.code())));
    }

    @Test
    void pnl003CtxSourceAssignmentInQuerySubtree() {
        String src = "ctx._source = 1;";
        var ctx = new PainlessAnalyzer.PainlessScriptContext(true, false, false, Set.of());
        List<SemanticError> r = PainlessAnalyzer.analyze(src, ctx, Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "PNL-003".equals(e.code())));
    }

    @Test
    void pnl004RequiresReturn() {
        String src = "def x = 1;";
        var ctx = new PainlessAnalyzer.PainlessScriptContext(false, false, true, Set.of());
        List<SemanticError> r = PainlessAnalyzer.analyze(src, ctx, Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "PNL-004".equals(e.code())));
    }

    @Test
    void pnl007MathRandom() {
        String src = "return Math.random();";
        var ctx = PainlessAnalyzer.PainlessScriptContext.defaults();
        List<SemanticError> r = PainlessAnalyzer.analyze(src, ctx, Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "PNL-007".equals(e.code())));
    }
}
