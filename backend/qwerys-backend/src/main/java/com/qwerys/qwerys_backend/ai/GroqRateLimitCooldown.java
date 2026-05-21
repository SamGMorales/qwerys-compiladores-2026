package com.qwerys.qwerys_backend.ai;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remembers recent Groq 429 responses so {@link FallbackAiClient} can skip a slow
 * doomed primary call and go straight to OpenRouter for ~55 minutes.
 */
final class GroqRateLimitCooldown {

    private static final Duration COOLDOWN = Duration.ofMinutes(55);
    private static final AtomicLong rateLimitedUntilMs = new AtomicLong(0);

    private GroqRateLimitCooldown() {
    }

    static boolean isActive() {
        return System.currentTimeMillis() < rateLimitedUntilMs.get();
    }

    static void markRateLimited() {
        rateLimitedUntilMs.set(System.currentTimeMillis() + COOLDOWN.toMillis());
    }

    static void clear() {
        rateLimitedUntilMs.set(0);
    }
}
