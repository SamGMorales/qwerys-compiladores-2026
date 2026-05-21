package com.qwerys.qwerys_backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat-completions client.
 *
 * <p>Intentionally not annotated with {@code @Component}: instances are
 * created explicitly in {@link AiClientConfig} — one for the primary provider
 * (Groq) and, when configured, one for the fallback provider (OpenRouter).
 * This avoids duplicate {@code AiClient} beans and keeps Spring's DI clean.
 */
public class GroqAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(GroqAiClient.class);

    private final String apiKey;
    private final String model;
    private final String providerLabel;
    /** OpenRouter recommends HTTP-Referer + X-Title; null for Groq primary. */
    private final String httpReferer;
    private final String appTitle;
    /**
     * When non-null (OpenRouter fallback only), prefers these provider slugs first.
     * {@code allow_fallbacks} stays true so OpenRouter can still use other hosts if saturated.
     */
    private final List<String> openRouterProviderOrder;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * Primary constructor — reads primary properties from {@link AiProperties}.
     * Called by {@link AiClientConfig} for the Groq primary instance.
     */
    public GroqAiClient(AiProperties properties, ObjectMapper objectMapper) {
        this(properties.getApiKey(),
             properties.getBaseUrl(),
             properties.getModel(),
             properties.getProvider(),
             null,
             null,
             objectMapper);

        if (properties.isConfigured()) {
            log.info("Primary AI client: configured (provider={}, model={})",
                     properties.getProvider(), properties.getModel());
        } else {
            log.warn("Primary AI client: no api-key — set ai.api-key=gsk_... in application.properties "
                    + "(use api-key with a hyphen, not api.key)");
        }
    }

    /**
     * Generic constructor for any OpenAI-compatible endpoint.
     * Used by {@link AiClientConfig} to build the optional fallback instance.
     */
    public GroqAiClient(String apiKey, String baseUrl, String model,
                        String providerLabel, ObjectMapper objectMapper) {
        this(apiKey, baseUrl, model, providerLabel, null, null, objectMapper);
    }

    public GroqAiClient(String apiKey, String baseUrl, String model,
                        String providerLabel, String httpReferer, String appTitle,
                        ObjectMapper objectMapper) {
        this(apiKey, baseUrl, model, providerLabel, httpReferer, appTitle, objectMapper, null);
    }

    /**
     * @param openRouterProviderOrder optional; only set for OpenRouter fallback routing hints
     */
    public GroqAiClient(String apiKey, String baseUrl, String model,
                        String providerLabel, String httpReferer, String appTitle,
                        ObjectMapper objectMapper, List<String> openRouterProviderOrder) {
        this.apiKey = apiKey;
        this.model = model;
        this.providerLabel = providerLabel != null ? providerLabel : "ai";
        this.httpReferer = httpReferer;
        this.appTitle = appTitle;
        this.openRouterProviderOrder = openRouterProviderOrder == null || openRouterProviderOrder.isEmpty()
                ? null
                : List.copyOf(openRouterProviderOrder);
        this.objectMapper = objectMapper;

        // Explicit timeouts: connect in ≤5 s, read response in ≤45 s.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(45));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String providerName() {
        return providerLabel + ":" + model;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, false);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, boolean jsonObjectMode) {
        if (!isAvailable()) {
            throw new IllegalStateException(providerLabel + " API key is not configured");
        }

        Map<String, Object> body = buildBody(systemPrompt, userPrompt, jsonObjectMode);

        try {
            String raw;
            try {
                raw = postChatCompletion(body);
            } catch (RestClientResponseException ex) {
                // Some OpenRouter models reject response_format — retry once without JSON mode.
                if (jsonObjectMode && ex.getStatusCode().value() == 400) {
                    log.warn("{} API 400 with json_object mode; retrying without response_format", providerLabel);
                    raw = postChatCompletion(buildBody(systemPrompt, userPrompt, false));
                } else {
                    throw ex;
                }
            }

            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException("Empty response from Groq");
            }

            JsonNode root = objectMapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new IllegalStateException("No content in Groq response");
            }
            return stripMarkdownFences(content.asText().trim());
        } catch (RestClientResponseException ex) {
            // HTTP 4xx/5xx — log the provider error body (rate_limit_exceeded,
            // invalid_api_key, model_not_found, etc.)
            String errorBody = ex.getResponseBodyAsString();
            log.warn("{} API HTTP {}: {}", providerLabel, ex.getStatusCode(), errorBody);
            throw new IllegalStateException(
                    providerLabel + " API HTTP " + ex.getStatusCode() + ": " + errorBody, ex);
        } catch (RestClientException ex) {
            // Network-level failure (timeout, DNS, TLS, etc.)
            log.warn("{} API network error: {}", providerLabel, ex.getMessage());
            throw new IllegalStateException(providerLabel + " network error: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            log.warn("Failed to parse {} response: {}", providerLabel, ex.getMessage());
            throw new IllegalStateException("Invalid " + providerLabel + " response: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> buildBody(String systemPrompt, String userPrompt, boolean jsonObjectMode) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", 0.15);
        body.put("max_tokens", 4096);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        if (jsonObjectMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        applyOpenRouterProviderRouting(body);
        return body;
    }

    /**
     * Prefer fast OpenRouter hosts first; keep fallbacks enabled so other providers are still tried.
     */
    private void applyOpenRouterProviderRouting(Map<String, Object> body) {
        if (openRouterProviderOrder == null) {
            return;
        }
        body.put("provider", Map.of(
                "order", openRouterProviderOrder,
                "allow_fallbacks", true));
    }

    private String postChatCompletion(Map<String, Object> body) {
        var req = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey);
        if (httpReferer != null && !httpReferer.isBlank()) {
            req = req.header("HTTP-Referer", httpReferer);
        }
        if (appTitle != null && !appTitle.isBlank()) {
            req = req.header("X-Title", appTitle);
        }
        return req.body(body).retrieve().body(String.class);
    }

    private static String stripMarkdownFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int end = text.lastIndexOf("```");
            if (end >= 0) {
                text = text.substring(0, end);
            }
        }
        return text.trim();
    }
}
