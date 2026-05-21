package com.qwerys.qwerys_backend.service;

import java.util.Set;

/**
 * Repairs common LLM JSON mistakes before complement parsing.
 * <p>
 * Typical breakage: pseudo string-concatenation copied from the user's query
 * ({@code '" + userId + "'}) inside a JSON string value without escaping inner
 * quotes; Jackson then sees {@code +} outside a string and fails.
 */
final class ComplementJsonRepair {

    private static final Set<String> STRING_VALUE_KEYS = Set.of(
            "pedagogy",
            "optimizationNotes",
            "reason",
            "comment",
            "message",
            "suggestion",
            "description",
            "originalFragment",
            "optimizedFragment",
            "original",
            "optimized",
            "correctedQuery",
            "explanation",
            "referenceId",
            "forErrorCode",
            "code",
            "ruleId",
            "impact",
            "severity",
            "verdict",
            "value");

    private ComplementJsonRepair() {
    }

    static String repair(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        String repaired = json;
        for (String key : STRING_VALUE_KEYS) {
            repaired = repairQuotedStringField(repaired, key);
        }
        return repaired;
    }

    /**
     * For {@code "fieldName": "..."} values, escape premature {@code "} that the
     * model emitted before {@code + identifier +} pseudo-concatenation.
     */
    private static String repairQuotedStringField(String json, String fieldName) {
        String marker = "\"" + fieldName + "\": \"";
        StringBuilder out = new StringBuilder(json.length() + 32);
        int pos = 0;
        while (pos < json.length()) {
            int idx = json.indexOf(marker, pos);
            if (idx < 0) {
                out.append(json, pos, json.length());
                break;
            }
            out.append(json, pos, idx + marker.length());
            int scan = idx + marker.length();
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (scan < json.length()) {
                char c = json.charAt(scan);
                if (c == '\\' && scan + 1 < json.length()) {
                    value.append(c).append(json.charAt(scan + 1));
                    scan += 2;
                    continue;
                }
                if (c == '"') {
                    int k = scan + 1;
                    while (k < json.length() && Character.isWhitespace(json.charAt(k))) {
                        k++;
                    }
                    if (k < json.length() && json.charAt(k) == '+') {
                        value.append("\\\"");
                        scan++;
                        continue;
                    }
                    if (k < json.length()
                            && (json.charAt(k) == ','
                            || json.charAt(k) == '}'
                            || json.charAt(k) == ']')) {
                        out.append(value);
                        out.append('"');
                        pos = scan + 1;
                        closed = true;
                        break;
                    }
                    value.append("\\\"");
                    scan++;
                    continue;
                }
                value.append(c);
                scan++;
            }
            if (!closed) {
                out.append(value);
                pos = json.length();
            }
        }
        return out.toString();
    }
}
