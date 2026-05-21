package com.qwerys.qwerys_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerys.qwerys_backend.ai.AiClient;
import com.qwerys.qwerys_backend.model.ai.SuggestQueryRequest;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSuggestionServiceFallbackTest {

    @Test
    void suggestQuery_fallsBackWhenAiThrows() {
        AiClient failingClient = new AiClient() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String providerName() {
                return "mock";
            }

            @Override
            public String complete(String systemPrompt, String userPrompt) {
                throw new IllegalStateException("Groq down");
            }
        };

        var service = new AiSuggestionService(
                failingClient,
                new RuleBasedAiFallback(),
                new ComplementAnalysisParser(new ObjectMapper()),
                new ComplementAnalysisEnricher(mock(QueryAnalysisService.class)));
        var response = service.suggestQuery(
                new SuggestQueryRequest("usuarios activos", "mysql", null, "es"));

        assertTrue(response.success());
        assertFalse(response.aiAvailable());
        assertEquals("rule-based-fallback", response.provider());
        assertFalse(response.result().isBlank());
    }
}
