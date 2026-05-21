package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.PostgreSQLDialectAnalyzer;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL PL/pgSQL–specific checks on procedural and routine definitions.
 *
 * <p>Rule identifiers: {@code PROC-PG-001} … {@code PROC-PG-010}.
 */
public class PlPgSqlAnalyzer extends PostgreSQLDialectAnalyzer {

    private static final Pattern CREATE_FUNCTION =
            Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern LANGUAGE_PLPGSQL =
            Pattern.compile("\\bLANGUAGE\\s+plpgsql\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SECURITY_DEFINER =
            Pattern.compile("\\bSECURITY\\s+DEFINER\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SET_SEARCH_PATH =
            Pattern.compile("\\bSET\\s+search_path\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern VOLATILITY_MODIFIER =
            Pattern.compile("\\b(STABLE|IMMUTABLE)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DOLLAR_BLOCKS =
            Pattern.compile("\\$(\\w*)\\$([\\s\\S]*?)\\$(?:\\1)\\$");

    private static final Pattern RETURNS_SETOF =
            Pattern.compile("\\bRETURNS\\s+SETOF\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern RETURNS_TRIGGER =
            Pattern.compile("\\bRETURNS\\s+TRIGGER\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern RETURN_NEW_OLD_NULL =
            Pattern.compile("\\bRETURN\\s+(?:NEW|OLD|NULL)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern NOTIFY_OR_LISTEN =
            Pattern.compile("\\b(NOTIFY|LISTEN)\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern EXECUTE_WITH_USING =
            Pattern.compile("(?is)\\bEXECUTE\\s+([^;]*)\\bUSING\\b", Pattern.DOTALL);

    private static final Pattern EXECUTE_LINE =
            Pattern.compile("(?is)\\bEXECUTE\\s+([^;]*);");

    private static final Pattern GET_DIAG_STMT =
            Pattern.compile("(?is)\\bGET\\s+DIAGNOSTICS\\s+([^;]+);");

    private static final Pattern MUTATING_DML =
            Pattern.compile("\\b(INSERT|UPDATE|DELETE|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern WHEN_OTHERS =
            Pattern.compile("(?is)\\bWHEN\\s+OTHERS\\s+THEN\\s*");

    private static final Pattern STATEMENT_COMPLEXITY_HINT =
            Pattern.compile("\\b(FOR\\s+\\w+\\s+IN\\s+|WHILE\\b|\\bLOOP\\b|\\bEXCEPTION\\b)",
                    Pattern.CASE_INSENSITIVE);

    public PlPgSqlAnalyzer(AstNode root, String rawSql) {
        this(root, rawSql, Locale.ENGLISH);
    }

    public PlPgSqlAnalyzer(AstNode root, String rawSql, Locale uiLocale) {
        super(root, rawSql, uiLocale != null ? uiLocale : Locale.ENGLISH);
    }

    @Override
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        super.analyzeDialectSpecific(errors);
        if (rawSql == null || rawSql.isBlank()) {
            return;
        }
        checkProcPg001(errors);
        checkProcPg002(errors);
        checkProcPg003(errors);
        checkProcPg004(errors);
        checkProcPg005(errors);
        checkProcPg006(errors);
        checkProcPg007(errors);
        checkProcPg008(errors);
        checkProcPg009(errors);
        checkProcPg010(errors);
    }

    private boolean isRoutineContext() {
        return CREATE_FUNCTION.matcher(rawSql).find();
    }

    private List<String> dollarBodies() {
        List<String> bodies = new ArrayList<>();
        Matcher m = DOLLAR_BLOCKS.matcher(rawSql);
        while (m.find()) {
            bodies.add(m.group(2));
        }
        return bodies;
    }

    private String concatenatedBodies() {
        List<String> b = dollarBodies();
        return b.isEmpty() ? rawSql : String.join("\n", b);
    }

    private void checkProcPg001(List<SemanticError> errors) {
        if (!CREATE_FUNCTION.matcher(rawSql).find()) {
            return;
        }
        if (LANGUAGE_PLPGSQL.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-PG-001",
                t("CREATE FUNCTION routine without explicit LANGUAGE plpgsql",
                        "Función o rutina CREATE FUNCTION sin LANGUAGE plpgsql explícito"),
                t("Add LANGUAGE plpgsql at the end of the header (or before the body) for PL/pgSQL routines.",
                        "Añade LANGUAGE plpgsql al final del encabezado (o antes del cuerpo) para rutinas escritas en PL/pgSQL."),
                SemanticError.Severity.ERROR));
    }

    private void checkProcPg002(List<SemanticError> errors) {
        if (!SECURITY_DEFINER.matcher(rawSql).find()) {
            return;
        }
        if (SET_SEARCH_PATH.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-PG-002",
                t("SECURITY DEFINER without fixed SET search_path",
                        "SECURITY DEFINER sin SET search_path fijo"),
                t("search_path hijacking risk in SECURITY DEFINER functions; use SET search_path = pg_temp, pg_catalog, or another explicit fixed list.",
                        "Riesgo de hijacking del search_path en funciones SECURITY DEFINER; usa SET search_path = pg_temp, pg_catalog o cualquier lista explícita y fija."),
                SemanticError.Severity.WARNING));
    }

    private void checkProcPg003(List<SemanticError> errors) {
        if (!isRoutineContext() || !VOLATILITY_MODIFIER.matcher(rawSql).find()) {
            return;
        }
        String inner = concatenatedBodies();
        if (!MUTATING_DML.matcher(inner).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-PG-003",
                t("STABLE or IMMUTABLE function with mutating DML detected",
                        "Función STABLE o IMMUTABLE con DML modificador detectado"),
                t("A function declared IMMUTABLE/STABLE must not run INSERT/UPDATE/DELETE/TRUNCATE; use VOLATILE or fix the body.",
                        "Una función declarada IMMUTABLE/STABLE no debe ejecutar INSERT/UPDATE/DELETE/TRUNCATE; use VOLATILE o corrija el cuerpo."),
                SemanticError.Severity.ERROR));
    }

    private void checkProcPg004(List<SemanticError> errors) {
        if (!RETURNS_SETOF.matcher(rawSql).find()) {
            return;
        }
        Matcher mBad = Pattern.compile("(?ims)\\bRETURN\\s*;").matcher(concatenatedBodies());
        if (mBad.find()) {
            errors.add(new SemanticError(
                    "PROC-PG-004",
                    t("RETURNS SETOF function with RETURN without valid SETOF rows",
                            "Función RETURNS SETOF con RETURN sin filas SETOF válido"),
                    t("Use RETURN QUERY, RETURN QUERY SELECT …, or RETURN NEXT row to return sets per the SETOF contract.",
                            "Use RETURN QUERY, RETURN QUERY SELECT …, o RETURN NEXT row para devolver conjuntos conforme el contrato SETOF."),
                    SemanticError.Severity.ERROR));
        }
    }

    private void checkProcPg005(List<SemanticError> errors) {
        if (!RETURNS_TRIGGER.matcher(rawSql).find()) {
            return;
        }
        String inner = concatenatedBodies();
        if (RETURN_NEW_OLD_NULL.matcher(inner).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-PG-005",
                t("Trigger function without RETURN NEW / OLD / NULL",
                        "Función de disparador sin RETURN NEW / OLD / NULL"),
                t("FOR EACH ROW PL/pgSQL triggers must end executable paths with RETURN NEW, RETURN OLD, or RETURN NULL as appropriate.",
                        "Las triggers FOR EACH ROW en PL/pgSQL deben terminar rutas ejecutables con RETURN NEW, RETURN OLD o RETURN NULL cuando aplica."),
                SemanticError.Severity.ERROR));
    }

    private void checkProcPg006(List<SemanticError> errors) {
        if (!NOTIFY_OR_LISTEN.matcher(rawSql).find()) {
            return;
        }
        String inner = concatenatedBodies();
        long hits = STATEMENT_COMPLEXITY_HINT.matcher(inner).results().count();
        if (hits < 3) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-PG-006",
                t("NOTIFY or LISTEN in lengthy procedural block",
                        "NOTIFY o LISTEN en bloque procedural extenso"),
                t("Notifications inside long procedural work may become effective late relative to commit; validate transaction scope and listeners.",
                        "Las notificaciones dentro de trabajo procedural largo pueden quedar efectivas tarde respecto al commit; valide vida de la transacción y listeners."),
                SemanticError.Severity.WARNING));
    }

    private void checkProcPg007(List<SemanticError> errors) {
        Matcher mDyn = EXECUTE_LINE.matcher(rawSql);
        boolean foundRisk = false;
        while (mDyn.find()) {
            String stmt = mDyn.group(0);
            if (!stmt.contains("||")) {
                continue;
            }
            if (!Pattern.compile("\\bquote_literal\\b", Pattern.CASE_INSENSITIVE).matcher(stmt).find()) {
                foundRisk = true;
                break;
            }
        }
        if (!foundRisk) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-PG-007",
                t("Dynamic EXECUTE construction without quote_literal where input is concatenated",
                        "Construcción dinámica EXECUTE sin quote_literal donde se concatena entrada"),
                t("SQL injection: use format(%I, %L), quote_ident, quote_literal, or USING-bound parameters instead of raw concatenation.",
                        "SQL injection: use format(%I, %L), quote_ident, quote_literal o parámetros con USING sobre valores, no concatenar texto crudo."),
                SemanticError.Severity.ERROR));
    }

    private void checkProcPg008(List<SemanticError> errors) {
        Matcher ex = EXECUTE_LINE.matcher(concatenatedBodies());
        while (ex.find()) {
            String stmt = ex.group(0);
            String frag = ex.group(1);
            if (frag == null || !frag.contains("||")) {
                continue;
            }
            if (!EXECUTE_WITH_USING.matcher(stmt).find()) {
                errors.add(new SemanticError(
                        "PROC-PG-008",
                        t("Dynamic EXECUTE: consider USING for parameters instead of concatenation only",
                                "EXECUTE dinámico: considera USING sobre parámetros en lugar solo de concatenación"),
                        t("Executing with USING … reduces formatting risks and implicit errors; migrate values as executable arguments.",
                                "Ejecutar con USING … reduce riesgos de formato y errores implícitos; migrar valores como argumentos ejecutables."),
                        SemanticError.Severity.INFO));
                return;
            }
        }
    }

    private void checkProcPg009(List<SemanticError> errors) {
        Matcher g = GET_DIAG_STMT.matcher(rawSql);
        while (g.find()) {
            String assigns = g.group(1);
            if (assigns == null || assigns.isBlank()) {
                continue;
            }
            Set<String> names = diagnosticsTargets(assigns);
            int stmtEnd = g.end();
            String after = rawSql.length() > stmtEnd ? rawSql.substring(stmtEnd) : "";
            for (String nm : names) {
                Pattern use = Pattern.compile("\\b" + Pattern.quote(nm) + "\\b", Pattern.CASE_INSENSITIVE);
                if (!use.matcher(after).find()) {
                    errors.add(new SemanticError(
                            "PROC-PG-009",
                            t("GET DIAGNOSTICS assigned " + nm + " but it does not appear later in the same script",
                                    "GET DIAGNOSTICS asignó " + nm + " pero no aparece después en el mismo script"),
                            t("Confirm you need that diagnostic or use it after the assignment.",
                                    "Confirme que realmente necesita ese diagnóstico o úselo tras la asignación."),
                            SemanticError.Severity.WARNING));
                }
            }
        }
    }

    private static Set<String> diagnosticsTargets(String assignsClause) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : assignsClause.split(",")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String lhs = p.substring(0, eq).trim();
            lhs = lhs.replaceFirst("(?i)^EXCEPTION\\s+", "").trim();
            int sp = lhs.lastIndexOf(' ');
            String cand = sp >= 0 ? lhs.substring(sp + 1).trim() : lhs;
            if (cand.matches("[A-Za-z_][\\w]*")) {
                out.add(cand);
            }
        }
        return out;
    }

    private void checkProcPg010(List<SemanticError> errors) {
        int from = 0;
        while (from < rawSql.length()) {
            Matcher m = WHEN_OTHERS.matcher(rawSql);
            if (!m.find(from)) {
                break;
            }
            int start = m.end();
            int end = findExceptionHandlerSnippetEnd(rawSql, start);
            String body = rawSql.substring(start, Math.min(end, rawSql.length()));
            if (!Pattern.compile("\\bRAISE\\b", Pattern.CASE_INSENSITIVE).matcher(body).find()) {
                errors.add(new SemanticError(
                        "PROC-PG-010",
                        t("WHEN OTHERS too generic without RAISE in handler",
                                "WHEN OTHERS demasiado genérico sin RAISE en el manejador"),
                        t("Prefer specific exceptions or at least propagate with RAISE EXCEPTION / NOTICE to avoid swallowing errors silently.",
                                "Preferir excepciones concretas o al menos propagar información con RAISE EXCEPTION / NOTICE para no tragarse errores silenciosamente."),
                        SemanticError.Severity.WARNING));
            }
            from = Math.max(from + 1, end);
        }
    }

    private static int findExceptionHandlerSnippetEnd(String sql, int from) {
        Pattern nextWhen = Pattern.compile("(?ims)^\\s*WHEN\\b");
        Pattern endKw = Pattern.compile("(?ims)^\\s*END\\s*(?:;|\\z)");
        Matcher mw = nextWhen.matcher(sql);
        Matcher me = endKw.matcher(sql);
        List<Integer> candidates = new ArrayList<>();
        mw.region(from, sql.length());
        if (mw.find()) {
            candidates.add(mw.start());
        }
        me.region(from, sql.length());
        if (me.find()) {
            candidates.add(me.start());
        }
        return candidates.isEmpty() ? sql.length() : candidates.stream().min(Integer::compare).orElse(sql.length());
    }
}
