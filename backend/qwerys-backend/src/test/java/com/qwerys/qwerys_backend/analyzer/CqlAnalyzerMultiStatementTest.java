package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CqlAnalyzerMultiStatementTest {

    private final CqlAnalyzer analyzer = new CqlAnalyzer();

    @Test
    void scriptWide_listMutationThresholdAcrossFragments() {
        List<String> stmts = List.of(
                "UPDATE ks.t SET c += 1 WHERE id = 1",
                "UPDATE ks.t SET c += 1 WHERE id = 1",
                "UPDATE ks.t SET c += 1 WHERE id = 1");
        List<SemanticError> wide = analyzer.scriptWideSemanticFindings(stmts, Locale.ENGLISH);
        assertTrue(wide.stream().anyMatch(e -> "CQL-LIST-001".equals(e.code())));
    }

    @Test
    void ingestFullScript_registryVisibleToLaterSelect() {
        List<String> all = List.of(
                "CREATE TABLE ks.users (id int PRIMARY KEY, name text);",
                "SELECT name FROM ks.users WHERE id = 1;");
        List<SemanticError> second = analyzer.ingestFullScriptAndAnalyzeOne(all, all.get(1), Locale.ENGLISH);
        assertFalse(second.stream().anyMatch(e -> "CQL-SYN-001".equals(e.code())));
    }
}
