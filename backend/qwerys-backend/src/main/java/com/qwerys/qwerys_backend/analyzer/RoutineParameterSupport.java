package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort parsing of routine {@code PARAMETER_LIST} text nodes from the AST.
 */
public final class RoutineParameterSupport {

    private RoutineParameterSupport() {
    }

    /**
     * Formal parameters (IN, OUT, IN OUT) as canonical names (uppercase, no leading {@code @}).
     */
    public static Set<String> canonicalFormalNames(AstNode parameterList) {
        return formalNamesWithDisplay(parameterList).keySet();
    }

    /**
     * Map canonical name → best display name (preserves {@code @} for T-SQL when present).
     */
    public static Map<String, String> formalNamesWithDisplay(AstNode parameterList) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String chunk : splitParameterChunks(parameterList)) {
            String raw = extractFirstParameterNameRaw(chunk);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String canon = canonical(raw);
            if (!canon.isEmpty()) {
                out.putIfAbsent(canon, raw);
            }
        }
        return out;
    }

    /**
     * Parameters that behave as read-only inputs ({@code IN} or default); excludes {@code OUT} and {@code IN OUT}.
     */
    public static Set<String> canonicalInOnlyNames(AstNode parameterList) {
        Set<String> names = new LinkedHashSet<>();
        for (String chunk : splitParameterChunks(parameterList)) {
            if (chunk.isEmpty()) {
                continue;
            }
            String u = chunk.toUpperCase(Locale.ROOT);
            if (u.contains(" OUT ") || u.contains("(OUT)") || u.contains("\tOUT ")
                    || u.startsWith("OUT ")) {
                continue;
            }
            if (u.contains(" INOUT ") || u.contains(" IN OUT ") || u.contains("READWRITE")) {
                continue;
            }
            String raw = extractFirstParameterNameRaw(chunk);
            if (raw == null) {
                continue;
            }
            names.add(canonical(raw));
        }
        return names;
    }

    static List<String> splitParameterChunks(AstNode paramList) {
        List<String> chunks = new ArrayList<>();
        if (paramList == null || paramList.getValue() == null) {
            return chunks;
        }
        String inner = paramList.getValue().trim();
        if (inner.startsWith("(") && inner.endsWith(")")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (inner.isBlank()) {
            return chunks;
        }
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                chunks.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString().trim());
        }
        return chunks;
    }

    /**
     * First identifier-like token after optional {@code IN}, {@code OUT}, {@code IN OUT} prefixes.
     */
    static String extractFirstParameterNameRaw(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return null;
        }
        String[] toks = chunk.trim().split("\\s+");
        int i = 0;
        while (i < toks.length) {
            String u = toks[i].toUpperCase(Locale.ROOT);
            if ("IN".equals(u)) {
                i++;
                if (i < toks.length && "OUT".equalsIgnoreCase(toks[i])) {
                    i++;
                }
                continue;
            }
            if ("OUT".equals(u)) {
                i++;
                continue;
            }
            if ("INOUT".equals(u)) {
                i++;
                continue;
            }
            break;
        }
        if (i >= toks.length) {
            return null;
        }
        String name = toks[i];
        String stripped = name.startsWith("@") ? name.substring(1) : name;
        if (stripped.isEmpty() || !Character.isLetter(stripped.charAt(0))) {
            return null;
        }
        return name;
    }

    static String canonical(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.startsWith("@")) {
            t = t.substring(1);
        }
        return t.toUpperCase(Locale.ROOT);
    }
}
