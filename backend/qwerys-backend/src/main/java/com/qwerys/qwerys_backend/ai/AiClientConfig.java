package com.qwerys.qwerys_backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires AI client beans.
 *
 * <p>A single {@link FallbackAiClient} is registered as {@code @Primary}.
 * It wraps the Groq primary and, when {@code ai.fallback-api-key} is set,
 * an OpenRouter (or any OpenAI-compatible) fallback instance.
 * Both instances are plain {@link GroqAiClient} objects — no Spring magic,
 * just different constructor arguments.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AiClientConfig.class);

    @Bean
    @Primary
    public AiClient aiClient(AiProperties properties, ObjectMapper objectMapper) {
        GroqAiClient primary = new GroqAiClient(properties, objectMapper);

        GroqAiClient fallbackClient = null;
        if (properties.isFallbackConfigured()) {
            fallbackClient = new GroqAiClient(
                    properties.getFallbackApiKey(),
                    properties.getFallbackBaseUrl(),
                    properties.getFallbackModel(),
                    properties.getFallbackProvider(),
                    properties.getFallbackHttpReferer(),
                    properties.getFallbackAppTitle(),
                    objectMapper,
                    OpenRouterFallbackRouting.PREFERRED_PROVIDER_ORDER);
            log.info("Fallback AI client configured (provider={}, model={}, openRouterOrder={})",
                     properties.getFallbackProvider(),
                     properties.getFallbackModel(),
                     OpenRouterFallbackRouting.PREFERRED_PROVIDER_ORDER);
        } else {
            log.info("Fallback AI client not configured (ai.fallback-api-key is blank) — Groq only");
        }

        return new FallbackAiClient(primary, fallbackClient);
    }
}
