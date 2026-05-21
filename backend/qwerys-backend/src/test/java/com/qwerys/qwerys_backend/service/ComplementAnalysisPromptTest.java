package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplementAnalysisPromptTest {

    @Test
    void buildComplementSystem_doesNotThrowOnFormatPlaceholders() throws Exception {
        ComplementAnalysisRequest req = new ComplementAnalysisRequest(
                "WITH ranked AS (SELECT * FROM t) SELECT * FROM ranked",
                "sqlserver",
                "es",
                false,
                true,
                "sql",
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(), null, null, null, null, null);

        Method m = AiSuggestionService.class.getDeclaredMethod(
                "buildComplementSystem", ComplementAnalysisRequest.class);
        m.setAccessible(true);

        String system = assertDoesNotThrow(() -> (String) m.invoke(null, req));
        assertTrue(system.contains("sqlserver"));
        assertTrue(system.contains("Return ONLY valid JSON"));
        assertFalse(system.contains("SCRIPT-LEVEL PASS"));
    }

    @Test
    void buildComplementSystem_includesScriptAddendumWhenScriptScope() throws Exception {
        ComplementAnalysisRequest req = new ComplementAnalysisRequest(
                "BEGIN; SELECT 1; COMMIT;",
                "postgresql",
                "en",
                false,
                true,
                "sql",
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                "SCRIPT",
                "BEGIN; SELECT 1; COMMIT;",
                null,
                2);

        Method m = AiSuggestionService.class.getDeclaredMethod(
                "buildComplementSystem", ComplementAnalysisRequest.class);
        m.setAccessible(true);

        String system = assertDoesNotThrow(() -> (String) m.invoke(null, req));
        assertTrue(system.contains("SCRIPT-LEVEL PASS"));
        assertTrue(system.contains("2 statement"));
    }
}
