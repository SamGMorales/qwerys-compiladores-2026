package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisAnalyzerCrossScriptTest {

    private final RedisAnalyzer analyzer = new RedisAnalyzer();

    @Test
    void crossScript_semicolonSeparatedMultiExec_hasNoExecWithoutMulti() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI; SET a 1; EXEC", Locale.ENGLISH);
        assertFalse(cross.stream().anyMatch(e -> "RDS-TX-002".equals(e.code())));
    }

    @Test
    void crossScript_unclosedMulti_emitsTx005() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI; SET a 1", Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "RDS-TX-005".equals(e.code())));
    }

    @Test
    void multiFragment_stripsTx002SoPerPieceDoesNotFalsePositive() {
        List<SemanticError> frag = analyzer.analyzeForMultiStatementFragment("EXEC", Locale.ENGLISH);
        assertFalse(frag.stream().anyMatch(e -> "RDS-TX-002".equals(e.code())));
    }

    @Test
    void crossScript_multiWithKeysStar_emitsPip001() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI; KEYS *; EXEC", Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "RDS-PIP-001".equals(e.code())));
    }

    @Test
    void crossScript_watchWithoutExec_emitsPip003() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("WATCH mykey", Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "RDS-PIP-003".equals(e.code())));
    }

    @Test
    void crossScript_watchUnwatch_noPip003() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("WATCH mykey; UNWATCH", Locale.ENGLISH);
        assertFalse(cross.stream().anyMatch(e -> "RDS-PIP-003".equals(e.code())));
    }

    @Test
    void crossScript_over1000Commands_emitsPip002Once() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1002; i++) {
            sb.append("PING").append('\n');
        }
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(sb.toString(), Locale.ENGLISH);
        long pip002 = cross.stream().filter(e -> "RDS-PIP-002".equals(e.code())).count();
        assertEquals(1, pip002);
    }
}
