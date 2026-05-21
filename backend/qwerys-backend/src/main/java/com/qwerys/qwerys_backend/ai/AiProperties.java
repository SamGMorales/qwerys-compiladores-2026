package com.qwerys.qwerys_backend.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /**
     * Bind with {@code ai.api-key=} in properties (not {@code ai.api.key}, which Spring
     * treats as a nested path and leaves this field empty).
     */

    /** groq | openrouter | openai */
    private String provider = "groq";

    private String apiKey = "";

    private String model = "llama-3.3-70b-versatile";

    private String baseUrl = "https://api.groq.com/openai/v1";

    // ─── Fallback (optional) ────────────────────────────────────────────────
    // Set ai.fallback-api-key to enable OpenRouter (or any OpenAI-compatible)
    // fallback. When Groq returns 429, times out, or is unconfigured, the
    // fallback provider is tried before dropping to the rule-based mentor.

    /** e.g. openrouter */
    private String fallbackProvider = "openrouter";

    /** sk-or-v1-... — leave blank to disable fallback */
    private String fallbackApiKey = "";

    private String fallbackModel = "meta-llama/llama-3.3-70b-instruct";

    private String fallbackBaseUrl = "https://openrouter.ai/api/v1";

    /** OpenRouter attribution — https://openrouter.ai/docs#request-headers */
    private String fallbackHttpReferer = "http://localhost:4200";

    private String fallbackAppTitle = "QWERYS";

    // ─── Primary getters/setters ─────────────────────────────────────────────

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    // ─── Fallback getters/setters ─────────────────────────────────────────────

    public String getFallbackProvider() {
        return fallbackProvider;
    }

    public void setFallbackProvider(String fallbackProvider) {
        this.fallbackProvider = fallbackProvider;
    }

    public String getFallbackApiKey() {
        return fallbackApiKey;
    }

    public void setFallbackApiKey(String fallbackApiKey) {
        this.fallbackApiKey = fallbackApiKey;
    }

    public String getFallbackModel() {
        return fallbackModel;
    }

    public void setFallbackModel(String fallbackModel) {
        this.fallbackModel = fallbackModel;
    }

    public String getFallbackBaseUrl() {
        return fallbackBaseUrl;
    }

    public void setFallbackBaseUrl(String fallbackBaseUrl) {
        this.fallbackBaseUrl = fallbackBaseUrl;
    }

    public boolean isFallbackConfigured() {
        return fallbackApiKey != null && !fallbackApiKey.isBlank();
    }

    public String getFallbackHttpReferer() {
        return fallbackHttpReferer;
    }

    public void setFallbackHttpReferer(String fallbackHttpReferer) {
        this.fallbackHttpReferer = fallbackHttpReferer;
    }

    public String getFallbackAppTitle() {
        return fallbackAppTitle;
    }

    public void setFallbackAppTitle(String fallbackAppTitle) {
        this.fallbackAppTitle = fallbackAppTitle;
    }
}
