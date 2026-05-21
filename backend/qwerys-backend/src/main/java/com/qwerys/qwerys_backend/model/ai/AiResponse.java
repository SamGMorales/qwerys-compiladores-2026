package com.qwerys.qwerys_backend.model.ai;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiResponse(
        boolean success,
        String result,
        String error,
        boolean aiAvailable,
        String provider,
        Long responseTimeMs
) {
    public static AiResponse ok(String result, boolean aiAvailable, String provider, long ms) {
        return new AiResponse(true, result, null, aiAvailable, provider, ms);
    }

    public static AiResponse fail(String error, boolean aiAvailable, String provider, long ms) {
        return new AiResponse(false, null, error, aiAvailable, provider, ms);
    }
}
