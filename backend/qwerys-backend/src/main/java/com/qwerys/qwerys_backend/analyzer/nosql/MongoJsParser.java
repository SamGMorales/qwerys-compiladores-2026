package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lightweight scan of MongoDB server-side JavaScript snippets used in {@code $where} strings and
 * {@code $function.body}. Not a full JS parser — string-aware heuristics for risky patterns.
 */
public final class MongoJsParser {

    private static final Pattern WORD_THIS = Pattern.compile("\\bthis\\b");
    private static final Pattern CALL_EVAL = Pattern.compile("\\beval\\s*\\(");
    private static final Pattern CALL_REQUIRE = Pattern.compile("\\brequire\\s*\\(");
    private static final Pattern CALL_FUNCTION = Pattern.compile("\\bFunction\\s*\\(");
    private static final Pattern NEW_FUNCTION = Pattern.compile("\\bnew\\s+Function\\s*\\(");

    private MongoJsParser() {}

    /**
     * @param jsCode   raw JavaScript (may be empty)
     * @param uiLocale UI locale for messages
     * @return findings to merge with {@code MongoDbAnalyzer} output; never {@code null}
     */
    public static List<SemanticError> analyze(String jsCode, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        Locale loc = uiLocale != null ? uiLocale : Locale.ENGLISH;
        boolean es = loc.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        if (jsCode == null || jsCode.isBlank()) {
            return out;
        }
        String stripped = stripStringLiteralsForScan(jsCode);
        if (WORD_THIS.matcher(stripped).find()) {
            out.add(new SemanticError(
                    "MGO-JS-101",
                    es ? "El código usa 'this' para leer campos del documento en el servidor"
                            : "The snippet uses 'this' to read document fields on the server",
                    es ? "Prefiera operadores de consulta o $expr; el JS en servidor es costoso y difícil de auditar."
                            : "Prefer query operators or $expr; server-side JS is costly and hard to audit.",
                    SemanticError.Severity.INFO));
        }
        if (CALL_EVAL.matcher(stripped).find()) {
            out.add(dangerous(es, "eval", "eval()"));
        }
        if (CALL_REQUIRE.matcher(stripped).find()) {
            out.add(dangerous(es, "require", "require()"));
        }
        if (CALL_FUNCTION.matcher(stripped).find() || NEW_FUNCTION.matcher(stripped).find()) {
            out.add(dangerous(es, "Function", "Function() / new Function()"));
        }
        return out;
    }

    private static SemanticError dangerous(boolean es, String name, String token) {
        return new SemanticError(
                "MGO-JS-102",
                es ? "Llamada peligrosa detectada: " + token
                        : "Dangerous call detected: " + token,
                es ? "Evite " + name + " en código MongoDB; puede abrir vectores de abuso o fugas."
                        : "Avoid " + name + " in MongoDB-side JS; it can enable abuse or leakage vectors.",
                SemanticError.Severity.ERROR);
    }

    /**
     * Replaces '...' and "..." and `...` spans with spaces so regexes do not match inside literal text.
     */
    static String stripStringLiteralsForScan(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\'' || c == '\"') {
                char q = c;
                b.append(' ');
                i++;
                while (i < s.length()) {
                    char ch = s.charAt(i);
                    if (ch == '\\' && i + 1 < s.length()) {
                        i += 2;
                        continue;
                    }
                    if (ch == q) {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '`') {
                b.append(' ');
                i++;
                while (i < s.length()) {
                    char ch = s.charAt(i);
                    if (ch == '\\' && i + 1 < s.length()) {
                        i += 2;
                        continue;
                    }
                    if (ch == '`') {
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    b.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                b.append("  ");
                i += 2;
                while (i + 1 < s.length()) {
                    if (s.charAt(i) == '*' && s.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    b.append(' ');
                    i++;
                }
                continue;
            }
            b.append(c);
            i++;
        }
        return b.toString();
    }
}
