package com.qwerys.qwerys_backend.ai;

/**
 * Abstraction over external LLM providers (Groq today, OpenAI tomorrow).
 */
public interface AiClient {

    boolean isAvailable();

    String providerName();

    String complete(String systemPrompt, String userPrompt);

    /** When true, Groq uses JSON object response mode for structured outputs. */
    default String complete(String systemPrompt, String userPrompt, boolean jsonObjectMode) {
        return complete(systemPrompt, userPrompt);
    }
}
