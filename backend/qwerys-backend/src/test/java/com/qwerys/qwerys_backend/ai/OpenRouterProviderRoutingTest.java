package com.qwerys.qwerys_backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
class OpenRouterProviderRoutingTest {

    @Test
    void fallbackClient_includesProviderOrderWithAllowFallbacks() throws Exception {
        GroqAiClient client = new GroqAiClient(
                "sk-test",
                "https://openrouter.ai/api/v1",
                "meta-llama/llama-3.3-70b-instruct",
                "openrouter",
                "http://localhost:4200",
                "QWERYS",
                new ObjectMapper(),
                OpenRouterFallbackRouting.PREFERRED_PROVIDER_ORDER);

        Method buildBody = GroqAiClient.class.getDeclaredMethod(
                "buildBody", String.class, String.class, boolean.class);
        buildBody.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) buildBody.invoke(
                client, "system", "user", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> provider = (Map<String, Object>) body.get("provider");
        assertEquals(OpenRouterFallbackRouting.PREFERRED_PROVIDER_ORDER, provider.get("order"));
        assertEquals(true, provider.get("allow_fallbacks"));
    }

    @Test
    void primaryGroqClient_doesNotIncludeProviderRouting() throws Exception {
        GroqAiClient client = new GroqAiClient(
                "gsk-test",
                "https://api.groq.com/openai/v1",
                "llama-3.3-70b-versatile",
                "groq",
                null,
                null,
                new ObjectMapper());

        Method buildBody = GroqAiClient.class.getDeclaredMethod(
                "buildBody", String.class, String.class, boolean.class);
        buildBody.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) buildBody.invoke(
                client, "system", "user", true);

        assertNull(body.get("provider"));
    }
}
