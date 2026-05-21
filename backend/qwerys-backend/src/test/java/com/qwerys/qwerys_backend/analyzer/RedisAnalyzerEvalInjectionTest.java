package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisAnalyzerEvalInjectionTest {

    private final RedisAnalyzer analyzer = new RedisAnalyzer();

    @Test
    void eval_semicolonInsideLuaScript_doesNotTriggerInjection() {
        List<SemanticError> out = analyzer.analyze(
                "EVAL \"return redis.call('GET', KEYS[1]);\" 1 mykey", Locale.ENGLISH);
        assertFalse(out.stream().anyMatch(e -> "RDS-INJ-001".equals(e.code())));
    }

    @Test
    void eval_semicolonAfterArgs_sameLine_triggersInjection() {
        List<SemanticError> out = analyzer.analyze(
                "EVAL \"return 1\" 0 ; PING", Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "RDS-INJ-001".equals(e.code())));
    }

    @Test
    void eval_multilineAfterScript_newlineInTail_okForInjectionHeuristic() {
        String raw = "EVAL \"return 1\" 0\nPING";
        List<SemanticError> out = analyzer.analyze(raw, Locale.ENGLISH);
        assertFalse(out.stream().anyMatch(e -> "RDS-INJ-001".equals(e.code())));
    }

    @Test
    void evalsha_unquotedDigest_tailWithSemicolon_triggersInjection() {
        List<SemanticError> out = analyzer.analyze(
                "EVALSHA deadbeefdeadbeefdeadbeefdeadbeefdeadbeef 0 ; PING", Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "RDS-INJ-001".equals(e.code())));
    }

    @Test
    void multilineTransaction_doesNotTriggerInjection() {
        String raw = "MULTI\nSET a 1\nEXEC";
        List<SemanticError> out = analyzer.analyze(raw, Locale.ENGLISH);
        assertFalse(out.stream().anyMatch(e -> "RDS-INJ-001".equals(e.code())));
    }

    @Test
    void jsonSetWithDollarPath_doesNotTriggerInjection() {
        List<SemanticError> out = analyzer.analyze("JSON.SET user:1 $ 1", Locale.ENGLISH);
        assertFalse(out.stream().anyMatch(e -> "RDS-INJ-001".equals(e.code())));
    }

    @Test
    void jsonSetWithTrailingNewline_doesNotTriggerInjection() {
        List<SemanticError> out = analyzer.analyze("JSON.SET doc $ 1\n", Locale.ENGLISH);
        assertFalse(out.stream().anyMatch(e -> "RDS-INJ-001".equals(e.code())));
    }

    @Test
    void sameLineSemicolonStillTriggersInjection() {
        List<SemanticError> out = analyzer.analyze("GET x; PING", Locale.ENGLISH);
        assertTrue(out.stream().anyMatch(e -> "RDS-INJ-001".equals(e.code())));
    }
}
