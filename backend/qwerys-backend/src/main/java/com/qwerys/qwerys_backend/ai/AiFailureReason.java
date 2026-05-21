package com.qwerys.qwerys_backend.ai;

/**
 * Classifies why the LLM call failed so the rule-based fallback can
 * show the user an honest, actionable message instead of a generic one.
 *
 * <p>Detection is based on the exception message thrown by the AI client:
 * <ul>
 *   <li>{@link #RATE_LIMIT} — HTTP 429 / rate_limit_exceeded (Groq daily token cap)</li>
 *   <li>{@link #INVALID_KEY} — HTTP 401 / invalid_api_key</li>
 *   <li>{@link #NOT_CONFIGURED} — no API key set in application.properties</li>
 *   <li>{@link #TIMEOUT} — connect timeout or read timeout</li>
 *   <li>{@link #PARSE_ERROR} — response arrived but JSON could not be parsed</li>
 *   <li>{@link #UNKNOWN} — any other error</li>
 * </ul>
 */
public enum AiFailureReason {

    RATE_LIMIT,
    INVALID_KEY,
    NOT_CONFIGURED,
    TIMEOUT,
    PARSE_ERROR,
    UNKNOWN;

    /**
     * Infers the reason from the message of any exception thrown by an
     * {@link AiClient} implementation.  Never throws.
     */
    public static AiFailureReason from(Throwable ex) {
        if (ex == null) {
            return UNKNOWN;
        }
        String msg = ex.getMessage();
        if (msg == null) {
            return UNKNOWN;
        }
        String lower = msg.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("429") || lower.contains("rate_limit") || lower.contains("rate limit")) {
            return RATE_LIMIT;
        }
        if (lower.contains("401") || lower.contains("invalid_api_key") || lower.contains("invalid api key")
                || lower.contains("unauthorized") || lower.contains("authentication")) {
            return INVALID_KEY;
        }
        if (lower.contains("not configured") || lower.contains("api key is not configured")) {
            return NOT_CONFIGURED;
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("connect timed out")
                || lower.contains("read timed out") || lower.contains("socketexception")) {
            return TIMEOUT;
        }
        if (lower.contains("parse") || lower.contains("invalid groq response")
                || lower.contains("invalid response") || lower.contains("no content")) {
            return PARSE_ERROR;
        }
        return UNKNOWN;
    }
}
