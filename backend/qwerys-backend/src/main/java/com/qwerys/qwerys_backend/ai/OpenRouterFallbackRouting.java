package com.qwerys.qwerys_backend.ai;

import java.util.List;

/**
 * Default OpenRouter provider preference for the QWERYS fallback client (Llama 3.3 70B).
 * Slugs match OpenRouter model-page copy buttons. {@code allow_fallbacks} remains true
 * in {@link GroqAiClient} so other hosts are used when these are saturated.
 */
final class OpenRouterFallbackRouting {

    /** Throughput/latency leaders for {@code meta-llama/llama-3.3-70b-instruct} on OpenRouter. */
    static final List<String> PREFERRED_PROVIDER_ORDER = List.of(
            "Groq",
            "Friendli",
            "Together");

    private OpenRouterFallbackRouting() {
    }
}
