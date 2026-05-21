package com.qwerys.qwerys_backend.ai;

import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisRequest;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisResponse;
import com.qwerys.qwerys_backend.service.AiSuggestionService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live smoke test — skipped automatically when no API key is configured.
 * Verifies Groq complement path end-to-end (not rule-based fallback).
 */
@SpringBootTest
class GroqComplementLiveSmokeTest {

    @Autowired
    private AiClient aiClient;

    @Autowired
    private AiSuggestionService aiSuggestionService;

    @Test
    void complementAnalysis_usesGroqWhenKeyConfigured() {
        Assumptions.assumeTrue(aiClient.isAvailable(), "ai.api-key not set — skip live Groq test");

        ComplementAnalysisRequest req = new ComplementAnalysisRequest(
                "SELECT id, name FROM users WHERE active = true LIMIT 10",
                "postgresql",
                "es",
                false,
                false,
                "sql",
                "postgresql",
                null,
                null,
                List.of(),
                List.of(),
                List.of(), null, null, null, null, null);

        ComplementAnalysisResponse res = aiSuggestionService.complementAnalysis(req);

        assertTrue(res.success(), "expected success=true, error=" + res.error());
        assertFalse(
                res.provider() != null && res.provider().startsWith("rule-based"),
                "expected Groq provider, got: " + res.provider() + " pedagogy=" + res.pedagogy());
        assertFalse(
                res.pedagogy() != null && res.pedagogy().contains("Groq no respondió"),
                "should not use rule-based pedagogy when Groq works");
    }
}
