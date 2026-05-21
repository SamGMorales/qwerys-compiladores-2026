package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.AnalysisMessages;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Semantic rules for Elasticsearch Painless scripts (PNL-001 … PNL-007).
 */
public final class PainlessAnalyzer {

    private static final Pattern TRY_KW = Pattern.compile("\\btry\\b");

    private PainlessAnalyzer() {}

    /**
     * @param source  Painless source
     * @param context where the script runs (query vs aggs, return requirement, optional field schema)
     * @param uiLocale UI locale for messages
     */
    public static List<SemanticError> analyze(String source, PainlessScriptContext context, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return out;
        }
        Locale ui = uiLocale != null ? uiLocale : Locale.ENGLISH;
        PainlessScriptContext ctx = context != null ? context : PainlessScriptContext.defaults();

        PainlessAstNode ast;
        try {
            List<PainlessToken> toks = new PainlessLexer(source).tokenize();
            ast = new PainlessParser(toks).parse();
        } catch (PainlessLexer.LexException le) {
            out.add(new SemanticError(
                    "PNL-PARSE-ERROR",
                    AnalysisMessages.t(ui, "Painless lex error: " + le.getMessage(),
                            "Error léxico Painless: " + le.getMessage()),
                    AnalysisMessages.t(ui, "Check quotes, comments, and operators in the script.",
                            "Revise comillas, comentarios y operadores en el script."),
                    SemanticError.Severity.ERROR,
                    le.line(),
                    le.column()));
            return out;
        } catch (PainlessParser.ParseException pe) {
            out.add(new SemanticError(
                    "PNL-PARSE-ERROR",
                    AnalysisMessages.t(ui, "Painless parse error: " + pe.getMessage(),
                            "Error de sintaxis Painless: " + pe.getMessage()),
                    AnalysisMessages.t(ui, "Check braces, semicolons, if/while/for, and def syntax.",
                            "Revise llaves, punto y coma, if/while/for y la sintaxis def."),
                    SemanticError.Severity.ERROR,
                    pe.line(),
                    pe.column()));
            return out;
        }

        analyzeAst(ast, source, ctx, ui, out, false, false);
        checkRequiresReturn(ast, ctx, ui, out);
        analyzeDocScriptExceptionHeuristic(source, ui, out);
        analyzeMathRandom(source, ui, out);
        return out;
    }

    private static void analyzeMathRandom(String source, Locale ui, List<SemanticError> out) {
        if (source.contains("Math.random")) {
            out.add(new SemanticError(
                    "PNL-007",
                    AnalysisMessages.t(ui, "Math.random() makes script results non-reproducible",
                            "Math.random() hace que los resultados del script no sean reproducibles"),
                    AnalysisMessages.t(ui, "Use deterministic scoring or pass randomness via params.",
                            "Use puntuación determinista o pase aleatoriedad vía params."),
                    SemanticError.Severity.WARNING));
        }
    }

    private static void analyzeDocScriptExceptionHeuristic(String source, Locale ui, List<SemanticError> out) {
        if (!source.contains("doc[")) {
            return;
        }
        if (TRY_KW.matcher(source).find()) {
            return;
        }
        out.add(new SemanticError(
                "PNL-006",
                AnalysisMessages.t(ui, "Document access may throw ScriptException — not wrapped in try/catch",
                        "El acceso a doc puede lanzar ScriptException — no está envuelto en try/catch"),
                AnalysisMessages.t(ui, "Consider try/catch around doc[...] for missing or wrong-type fields.",
                        "Considere try/catch alrededor de doc[...] para campos ausentes o de tipo incorrecto."),
                SemanticError.Severity.INFO));
    }

    private static void analyzeAst(
            PainlessAstNode n,
            String rawSource,
            PainlessScriptContext ctx,
            Locale ui,
            List<SemanticError> out,
            boolean insideLoop,
            boolean insideFunction) {
        if (n == null) {
            return;
        }
        String k = n.kind();
        switch (k) {
            case PainlessAstNode.WHILE -> {
                if (n.children().size() >= 2
                        && isNonEscapingWhileCondition(n.children().get(0))
                        && !blockHasBreakOrReturn(n.children().get(1))) {
                    out.add(new SemanticError(
                            "PNL-001",
                            AnalysisMessages.t(ui, "Loop has no verifiable exit condition",
                                    "El bucle no tiene una condición de salida verificable"),
                            AnalysisMessages.t(ui, "Use a finite bound, mutate loop variables, or add break/return.",
                                    "Use un límite finito, modifique variables del bucle o añada break/return."),
                            SemanticError.Severity.ERROR,
                            n.line(),
                            null));
                }
                for (PainlessAstNode ch : n.children()) {
                    analyzeAst(ch, rawSource, ctx, ui, out, true, insideFunction);
                }
                return;
            }
            case PainlessAstNode.FOR -> {
                if (n.children().size() >= 1) {
                    PainlessAstNode body = n.children().get(n.children().size() - 1);
                    if (isInfiniteFor(n) && !blockHasBreakOrReturn(body)) {
                        out.add(new SemanticError(
                                "PNL-001",
                                AnalysisMessages.t(ui, "Loop has no verifiable exit condition",
                                        "El bucle no tiene una condición de salida verificable"),
                                AnalysisMessages.t(ui, "Add a finite condition, break, or return in the loop body.",
                                        "Añada una condición finita, break o return en el cuerpo del bucle."),
                                SemanticError.Severity.ERROR,
                                n.line(),
                                null));
                    }
                }
                for (PainlessAstNode ch : n.children()) {
                    analyzeAst(ch, rawSource, ctx, ui, out, true, insideFunction);
                }
                return;
            }
            case PainlessAstNode.FUNCTION_DEF -> {
                for (PainlessAstNode ch : n.children()) {
                    analyzeAst(ch, rawSource, ctx, ui, out, false, true);
                }
                return;
            }
            case PainlessAstNode.ASSIGNMENT -> {
                if (ctx.querySubtree() && n.children().size() >= 2) {
                    PainlessAstNode lhs = n.children().get(0);
                    if (assignsToCtxSource(lhs)) {
                        out.add(new SemanticError(
                                "PNL-003",
                                AnalysisMessages.t(ui, "Modifying ctx._source in a query script is not allowed",
                                        "Modificar ctx._source en un script de consulta no está permitido"),
                                AnalysisMessages.t(ui, "Use an update or ingest/reindex script to change _source.",
                                        "Use un script de update o ingest/reindex para cambiar _source."),
                                SemanticError.Severity.ERROR,
                                n.line(),
                                null));
                    }
                }
                for (PainlessAstNode ch : n.children()) {
                    analyzeAst(ch, rawSource, ctx, ui, out, insideLoop, insideFunction);
                }
                return;
            }
            case PainlessAstNode.INDEX -> {
                if (!ctx.schemaFields().isEmpty() && n.children().size() >= 2) {
                    PainlessAstNode base = n.children().get(0);
                    PainlessAstNode idx = n.children().get(1);
                    if (isDocKeyword(base) && idx.kind().equals(PainlessAstNode.LITERAL)) {
                        String field = idx.text();
                        if (field != null && !ctx.schemaFields().contains(field)) {
                            out.add(new SemanticError(
                                    "PNL-002",
                                    AnalysisMessages.t(ui, "doc field '" + field + "' is not in the provided schema",
                                            "El campo doc '" + field + "' no está en el esquema proporcionado"),
                                    AnalysisMessages.t(ui, "Fix the field name or refresh the index mapping/schema.",
                                            "Corrija el nombre del campo o actualice el mapping/esquema del índice."),
                                    SemanticError.Severity.ERROR,
                                    n.line(),
                                    null));
                        }
                    }
                }
                for (PainlessAstNode ch : n.children()) {
                    analyzeAst(ch, rawSource, ctx, ui, out, insideLoop, insideFunction);
                }
                return;
            }
            case PainlessAstNode.METHOD_CALL -> {
                if (ctx.aggsSubtree() && insideLoop && looksLikeLinearPass(n)) {
                    out.add(new SemanticError(
                            "PNL-005",
                            AnalysisMessages.t(ui, "Potential O(n) work inside a bucket aggregation script",
                                    "Posible trabajo O(n) dentro de un script de agregación por buckets"),
                            AnalysisMessages.t(ui, "Prefer doc-values, params, or precomputed metrics over per-bucket loops.",
                                    "Prefiera doc values, params o métricas precalculadas en lugar de bucles por bucket."),
                            SemanticError.Severity.WARNING,
                            n.line(),
                            null));
                }
                for (PainlessAstNode ch : n.children()) {
                    analyzeAst(ch, rawSource, ctx, ui, out, insideLoop, insideFunction);
                }
                return;
            }
            default -> {
                for (PainlessAstNode ch : n.children()) {
                    analyzeAst(ch, rawSource, ctx, ui, out, insideLoop, insideFunction);
                }
            }
        }
    }

    private static boolean looksLikeLinearPass(PainlessAstNode call) {
        if (call.children().isEmpty()) {
            return false;
        }
        PainlessAstNode target = call.children().get(0);
        String callee = dottedCallee(target);
        if (callee == null) {
            return false;
        }
        String lower = callee.toLowerCase(Locale.ROOT);
        return lower.endsWith(".stream")
                || lower.contains(".iterator")
                || lower.endsWith("foreach")
                || lower.contains("collection")
                || lower.contains("arrays.sort");
    }

    private static String dottedCallee(PainlessAstNode expr) {
        if (expr == null) {
            return null;
        }
        if (PainlessAstNode.FIELD.equals(expr.kind()) && expr.children().size() >= 2) {
            String base = dottedCallee(expr.children().get(0));
            PainlessAstNode right = expr.children().get(1);
            String name = right.text();
            if (base == null || name == null) {
                return name;
            }
            return base + "." + name;
        }
        if (PainlessAstNode.NAME.equals(expr.kind())) {
            return expr.text();
        }
        return null;
    }

    private static boolean isDocKeyword(PainlessAstNode base) {
        return PainlessAstNode.NAME.equals(base.kind()) && "doc".equals(base.text());
    }

    private static boolean assignsToCtxSource(PainlessAstNode lhs) {
        if (lhs == null) {
            return false;
        }
        if (PainlessAstNode.FIELD.equals(lhs.kind()) && lhs.children().size() >= 2) {
            PainlessAstNode base = lhs.children().get(0);
            PainlessAstNode fld = lhs.children().get(1);
            if (isCtxKeyword(base) && fld.text() != null && "_source".equals(fld.text())) {
                return true;
            }
            return assignsToCtxSource(base);
        }
        if (PainlessAstNode.INDEX.equals(lhs.kind()) && lhs.children().size() >= 2) {
            PainlessAstNode base = lhs.children().get(0);
            PainlessAstNode idx = lhs.children().get(1);
            if (isCtxKeyword(base) && idx.kind().equals(PainlessAstNode.LITERAL)) {
                String lit = idx.text();
                if ("_source".equals(lit)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCtxKeyword(PainlessAstNode n) {
        return PainlessAstNode.NAME.equals(n.kind()) && "ctx".equals(n.text());
    }

    private static boolean isInfiniteFor(PainlessAstNode forNode) {
        if (forNode.children().size() < 2) {
            return false;
        }
        PainlessAstNode cond = forNode.children().get(1);
        return cond == null || isTruthyLiteral(cond);
    }

    private static boolean isTruthyLiteral(PainlessAstNode n) {
        if (!PainlessAstNode.LITERAL.equals(n.kind())) {
            return false;
        }
        String t = n.text();
        if (t == null) {
            return false;
        }
        return "true".equals(t) || "1".equals(t);
    }

    private static boolean isNonEscapingWhileCondition(PainlessAstNode cond) {
        if (cond == null) {
            return true;
        }
        if (PainlessAstNode.LITERAL.equals(cond.kind())) {
            String t = cond.text();
            return "true".equals(t) || "1".equals(t);
        }
        if (PainlessAstNode.NAME.equals(cond.kind())) {
            String t = cond.text();
            return "true".equals(t);
        }
        if (PainlessAstNode.BINARY_EXPR.equals(cond.kind()) && cond.text() != null && "==".equals(cond.text())) {
            if (cond.children().size() == 2) {
                PainlessAstNode a = cond.children().get(0);
                PainlessAstNode b = cond.children().get(1);
                return literalsEqual(a, b);
            }
        }
        return false;
    }

    private static boolean literalsEqual(PainlessAstNode a, PainlessAstNode b) {
        if (!PainlessAstNode.LITERAL.equals(a.kind()) || !PainlessAstNode.LITERAL.equals(b.kind())) {
            return false;
        }
        String ta = a.text();
        String tb = b.text();
        return ta != null && ta.equals(tb);
    }

    private static boolean blockHasBreakOrReturn(PainlessAstNode block) {
        if (block == null) {
            return false;
        }
        if (PainlessAstNode.RETURN.equals(block.kind())) {
            return true;
        }
        for (PainlessAstNode ch : block.children()) {
            if (blockHasBreakOrReturn(ch)) {
                return true;
            }
        }
        return false;
    }

    private static boolean astContainsReturn(PainlessAstNode n) {
        if (n == null) {
            return false;
        }
        if (PainlessAstNode.RETURN.equals(n.kind())) {
            return true;
        }
        for (PainlessAstNode c : n.children()) {
            if (astContainsReturn(c)) {
                return true;
            }
        }
        return false;
    }

    /** After successful parse: require a return when the caller says so. */
    public static void checkRequiresReturn(PainlessAstNode chunk, PainlessScriptContext ctx, Locale ui,
            List<SemanticError> out) {
        if (chunk == null || !ctx.requireReturn()) {
            return;
        }
        if (!astContainsReturn(chunk)) {
            out.add(new SemanticError(
                    "PNL-004",
                    AnalysisMessages.t(ui, "Script must return a value in this context",
                            "El script debe devolver un valor en este contexto"),
                    AnalysisMessages.t(ui, "Add an explicit return (e.g. return score;).",
                            "Añada un return explícito (p. ej. return score;)."),
                    SemanticError.Severity.ERROR));
        }
    }

    public record PainlessScriptContext(
            boolean querySubtree,
            boolean aggsSubtree,
            boolean requireReturn,
            Set<String> schemaFields
    ) {
        public static PainlessScriptContext defaults() {
            return new PainlessScriptContext(false, false, false, Set.of());
        }

        public static PainlessScriptContext withSchema(Set<String> fields) {
            Set<String> f = fields == null ? Set.of() : Collections.unmodifiableSet(fields);
            return new PainlessScriptContext(false, false, false, f);
        }
    }
}
