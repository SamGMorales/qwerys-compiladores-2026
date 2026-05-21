package com.qwerys.qwerys_backend.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chains a primary {@link AiClient} (Groq) with an optional fallback
 * {@link AiClient} (OpenRouter or any OpenAI-compatible endpoint).
 *
 * <p>Strategy:
 * <ol>
 *   <li>If primary is configured, call it.</li>
 *   <li>On primary failure (429, timeout, 5xx, etc.) — if the fallback is
 *       configured, log the reason and try the fallback transparently.</li>
 *   <li>If the fallback also fails (or is not configured), rethrow a
 *       classified exception so {@link com.qwerys.qwerys_backend.service.AiSuggestionService}
 *       can show the user an honest message via
 *       {@link com.qwerys.qwerys_backend.service.RuleBasedAiFallback}.</li>
 * </ol>
 *
 * <p>Intentionally not annotated with {@code @Component}: it is registered as
 * the {@code @Primary} {@link AiClient} bean by {@link AiClientConfig}.
 */
public class FallbackAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackAiClient.class);

    private final AiClient primary;
    /** May be null when ai.fallback-api-key is not set. */
    private final AiClient fallback;

    public FallbackAiClient(AiClient primary, AiClient fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public boolean isAvailable() {
        return primary.isAvailable() || (fallback != null && fallback.isAvailable());
    }

    @Override
    public String providerName() {
        if (primary.isAvailable()) {
            return primary.providerName();
        }
        if (fallback != null && fallback.isAvailable()) {
            return fallback.providerName();
        }
        return "none";
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, userPrompt, false);
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, boolean jsonObjectMode) {
        // After a recent Groq 429, skip the primary call (saves several seconds per complement).
        if (fallback != null && fallback.isAvailable() && GroqRateLimitCooldown.isActive()) {
            if (primary.isAvailable()) {
                log.debug("Groq rate-limit cooldown — using fallback provider '{}' directly",
                        fallback.providerName());
            }
            return fallback.complete(systemPrompt, userPrompt, jsonObjectMode);
        }

        // ── Try primary ──────────────────────────────────────────────────────
        if (primary.isAvailable()) {
            try {
                return primary.complete(systemPrompt, userPrompt, jsonObjectMode);
            } catch (Exception primaryEx) {
                AiFailureReason reason = AiFailureReason.from(primaryEx);
                if (reason == AiFailureReason.RATE_LIMIT) {
                    GroqRateLimitCooldown.markRateLimited();
                }
                if (fallback != null && fallback.isAvailable()) {
                    log.warn("Primary AI client failed ({}: {}); trying fallback provider '{}'",
                             reason, primaryEx.getMessage(), fallback.providerName());
                    try {
                        String result = fallback.complete(systemPrompt, userPrompt, jsonObjectMode);
                        log.info("Fallback AI provider '{}' responded successfully", fallback.providerName());
                        return result;
                    } catch (Exception fallbackEx) {
                        log.warn("Fallback AI client '{}' also failed: {}",
                                 fallback.providerName(), fallbackEx.getMessage());
                        // Report the primary reason (more meaningful for the user)
                        throw classified(reason, primaryEx);
                    }
                }
                // No fallback configured — re-throw with classification prefix
                throw classified(reason, primaryEx);
            }
        }

        // ── Primary not configured — try fallback directly ───────────────────
        if (fallback != null && fallback.isAvailable()) {
            log.info("Primary AI not configured; using fallback provider '{}'", fallback.providerName());
            return fallback.complete(systemPrompt, userPrompt, jsonObjectMode);
        }

        // ── Nothing configured ───────────────────────────────────────────────
        throw new IllegalStateException("NOT_CONFIGURED: no AI provider configured (set ai.api-key or ai.fallback-api-key)");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Wraps {@code cause} in a new exception whose message is prefixed with
     * the {@link AiFailureReason} name so that downstream callers can detect
     * the reason without coupling to exception types.
     */
    private static IllegalStateException classified(AiFailureReason reason, Exception cause) {
        return new IllegalStateException(reason.name() + ": " + cause.getMessage(), cause);
    }
}
