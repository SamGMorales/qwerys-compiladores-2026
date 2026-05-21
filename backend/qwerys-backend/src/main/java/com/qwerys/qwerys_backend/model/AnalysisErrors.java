package com.qwerys.qwerys_backend.model;

import com.qwerys.qwerys_backend.analyzer.SqlParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for building {@link AnalysisError} lists from engine parsers.
 * Use this when adding SQL-like or other backends that reuse {@link SqlParser} syntax diagnostics.
 */
public final class AnalysisErrors {

    private AnalysisErrors() {}

    /**
     * Maps {@link SqlParser#getSyntaxErrors()} to API errors, parsing {@code line}/{@code column}
     * from each message when present.
     */
    public static List<AnalysisError> fromSqlParser(SqlParser parser, String code, String suggestion) {
        List<String> msgs = parser.getSyntaxErrors();
        if (msgs.isEmpty()) {
            return List.of();
        }
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "\\bat line (\\d+)[,\\s]+column (\\d+)\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        List<AnalysisError> list = new ArrayList<>(msgs.size());
        for (String msg : msgs) {
            java.util.regex.Matcher m = pat.matcher(msg);
            Integer ln  = null;
            Integer col = null;
            if (m.find()) {
                ln  = Integer.parseInt(m.group(1));
                col = Integer.parseInt(m.group(2));
            }
            list.add(new AnalysisError(code, msg, suggestion, ln, col));
        }
        return List.copyOf(list);
    }
}
