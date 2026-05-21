package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Heuristic scan of Cassandra UDF bodies ({@code LANGUAGE javascript} or {@code LANGUAGE java}).
 * Not a full parser — string-stripped patterns for Day 24G ({@code CAS-UDF-002}, {@code CAS-UDF-003}).
 */
public final class CassandraJsParser {

    private static final Pattern FOR_WHILE =
            Pattern.compile("\\b(for|while)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LARGE_NUMERIC_BOUND =
            Pattern.compile("\\bfor\\s*\\([^)]*?[<>=]+\\s*(\\d{4,})\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern[] JS_IMPURE = new Pattern[] {
            Pattern.compile("\\beval\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\brequire\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFunction\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnew\\s+Function\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bimport\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfetch\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bXMLHttpRequest\\b"),
            Pattern.compile("\\bjava\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bPackages\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfs\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\breadFile\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bwriteFile\\s*\\(", Pattern.CASE_INSENSITIVE),
    };

    private static final Pattern[] JAVA_IMPURE = new Pattern[] {
            Pattern.compile("\\bjava\\.io\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bjava\\.net\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bjava\\.nio\\.file\\.", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bProcessBuilder\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bThread\\s*\\.\\s*sleep\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bSystem\\s*\\.\\s*in\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFileInputStream\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFileOutputStream\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFileReader\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFileWriter\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnew\\s+File\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnew\\s+URL\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bnew\\s+Socket\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bHttpURLConnection\\b"),
            Pattern.compile("\\bFiles\\s*\\.\\s*(read|write|lines)\\s*\\(", Pattern.CASE_INSENSITIVE),
    };

    private CassandraJsParser() {}

    /**
     * @param body     UDF source inside {@code AS '...'}
     * @param javaLang true for {@code LANGUAGE java}, false for {@code javascript}
     */
    public static List<SemanticError> analyzeUdfBody(String body, boolean javaLang, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        Locale loc = uiLocale != null ? uiLocale : Locale.ENGLISH;
        boolean es = loc.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        if (body == null || body.isBlank()) {
            return out;
        }
        String stripped = MongoJsParser.stripStringLiteralsForScan(body);
        loopRules(stripped, es, out);
        if (javaLang) {
            for (Pattern p : JAVA_IMPURE) {
                if (p.matcher(stripped).find()) {
                    out.add(impure(es));
                    break;
                }
            }
        } else {
            for (Pattern p : JS_IMPURE) {
                if (p.matcher(stripped).find()) {
                    out.add(impure(es));
                    break;
                }
            }
        }
        return out;
    }

    private static void loopRules(String stripped, boolean es, List<SemanticError> out) {
        int loops = 0;
        var m = FOR_WHILE.matcher(stripped);
        while (m.find()) {
            loops++;
        }
        if (loops >= 3) {
            out.add(new SemanticError(
                    "CAS-UDF-002",
                    es ? "UDF lenta bloquea queries — demasiados bucles detectados"
                            : "Slow UDF blocks queries — many loops detected",
                    es ? "Simplifique la lógica o mueva el trabajo fuera del cluster."
                            : "Simplify logic or move work outside the cluster.",
                    SemanticError.Severity.WARNING));
        }
        var lm = LARGE_NUMERIC_BOUND.matcher(stripped);
        if (lm.find()) {
            out.add(new SemanticError(
                    "CAS-UDF-002",
                    es ? "UDF lenta bloquea queries — bucle con límite numérico muy grande"
                            : "Slow UDF blocks queries — loop with a very large numeric bound",
                    es ? "Reduzca iteraciones en la UDF o procese en la aplicación."
                            : "Reduce iterations in the UDF or process in the application.",
                    SemanticError.Severity.WARNING));
        }
    }

    private static SemanticError impure(boolean es) {
        return new SemanticError(
                "CAS-UDF-003",
                es ? "UDF debe ser pura — se detectó E/S o llamada externa"
                        : "UDF must be pure — I/O or external call detected",
                es ? "Elimine acceso a red, disco o APIs no deterministas del cuerpo."
                        : "Remove network, disk, or non-deterministic APIs from the body.",
                SemanticError.Severity.ERROR);
    }
}
