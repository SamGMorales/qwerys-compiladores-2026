package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Semantic rules for Redis Lua scripts (codes LUA-001 … LUA-007).
 */
public final class LuaAnalyzer {

    private static final Pattern KEYS_BRACKET = Pattern.compile("\\bKEYS\\s*\\[", Pattern.CASE_INSENSITIVE);

    private LuaAnalyzer() {}

    /**
     * @param script        Lua source from {@code EVAL}
     * @param evalNumKeys   Redis {@code numkeys} argument, or {@code -1} if unknown
     * @param spanishUi     {@code true} for Spanish messages
     */
    public static List<SemanticError> analyze(String script, int evalNumKeys, boolean spanishUi) {
        List<SemanticError> out = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return out;
        }
        String src = script;
        int lines = countLines(src);
        if (lines > 50) {
            out.add(new SemanticError(
                    "LUA-006",
                    spanishUi ? "Script de más de 50 líneas" : "Script longer than 50 lines",
                    spanishUi ? "Considera dividir el script en funciones o módulos cargados con SCRIPT LOAD."
                            : "Consider splitting the script into functions or modules loaded with SCRIPT LOAD.",
                    SemanticError.Severity.INFO));
        }

        LuaAstNode ast;
        try {
            List<LuaToken> toks = new LuaLexer(src).tokenize();
            ast = new LuaParser(toks).parse();
        } catch (LuaLexer.LexException le) {
            out.add(new SemanticError(
                    "LUA-PARSE-ERROR",
                    spanishUi ? "Error léxico en Lua: " + le.getMessage() : "Lua lex error: " + le.getMessage(),
                    spanishUi ? "Revise comillas, corchetes largos y comentarios." : "Check quotes, long brackets, and comments.",
                    SemanticError.Severity.ERROR,
                    le.line(),
                    le.column()));
            return out;
        } catch (LuaParser.ParseException pe) {
            out.add(new SemanticError(
                    "LUA-PARSE-ERROR",
                    spanishUi ? "Error de sintaxis Lua: " + pe.getMessage() : "Lua parse error: " + pe.getMessage(),
                    spanishUi ? "Revise la estructura if/while/for/end y las expresiones." : "Check if/while/for/end structure and expressions.",
                    SemanticError.Severity.ERROR,
                    pe.line(),
                    pe.column()));
            return out;
        }

        analyzeAst(ast, spanishUi, out, false, false);
        analyzeKeysClusterMode(src, evalNumKeys, spanishUi, out);
        return out;
    }

    private static int countLines(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static void analyzeAst(
            LuaAstNode n,
            boolean es,
            List<SemanticError> out,
            boolean insideLoop,
            boolean insideTonumber) {
        if (n == null) {
            return;
        }
        String k = n.kind();
        if (LuaAstNode.FUNCTION_CALL.equals(k)) {
            if (!insideTonumber && isTonumberCall(n)) {
                for (int i = 1; i < n.children().size(); i++) {
                    analyzeAst(n.children().get(i), es, out, insideLoop, true);
                }
                return;
            }
            if (!n.children().isEmpty()) {
                String callee = calleeString(n.children().get(0));
                if ("redis.call".equals(callee)) {
                    out.add(new SemanticError(
                            "LUA-001",
                            es ? "Se usa redis.call(); redis.pcall() es más robusto" : "Using redis.call(); redis.pcall() is more robust",
                            es ? "Use redis.pcall para capturar errores de comando sin abortar el script."
                                    : "Use redis.pcall to catch command errors without aborting the script.",
                            SemanticError.Severity.WARNING,
                            n.line(),
                            null));
                    if (insideLoop) {
                        out.add(new SemanticError(
                                "LUA-007",
                                es ? "redis.call dentro de un bucle (sin pipeline de cliente)"
                                        : "redis.call inside a loop (no client-side pipeline)",
                                es ? "Cada iteración va al servidor; agrupe comandos con pipeline fuera de Lua o reduzca iteraciones."
                                        : "Each iteration hits the server; batch with a client pipeline outside Lua or reduce iterations.",
                                SemanticError.Severity.WARNING,
                                n.line(),
                                null));
                    }
                }
            }
            for (LuaAstNode ch : n.children()) {
                analyzeAst(ch, es, out, insideLoop, insideTonumber);
            }
            return;
        }
        if (LuaAstNode.ASSIGNMENT.equals(k)) {
            if (!n.localAssignment() && !n.children().isEmpty()
                    && isGlobalNameAssignment(n.children().get(0))) {
                out.add(new SemanticError(
                        "LUA-003",
                        es ? "Asignación a variable global (no local)" : "Assignment to a global (non-local) variable",
                        es ? "Rendimiento: use local en scripts Lua para evitar la tabla global."
                                : "Performance penalty: use local in Lua scripts to avoid the global table.",
                        SemanticError.Severity.WARNING,
                        n.line(),
                        null));
            }
            for (LuaAstNode ch : n.children()) {
                analyzeAst(ch, es, out, insideLoop, insideTonumber);
            }
            return;
        }
        if (LuaAstNode.BINARY_EXPR.equals(k)) {
            String op = n.text() == null ? "" : n.text();
            if (isArithmetic(op) && !insideTonumber) {
                for (LuaAstNode ch : n.children()) {
                    if (argvUsedWithoutTonumberWrap(ch)) {
                        out.add(new SemanticError(
                                "LUA-005",
                                es ? "ARGV[] usado en aritmética sin tonumber()" : "ARGV[] used in arithmetic without tonumber()",
                                es ? "Convierta con tonumber(ARGV[n]) antes de operar numéricamente."
                                        : "Wrap with tonumber(ARGV[n]) before numeric operations.",
                                SemanticError.Severity.WARNING,
                                ch.line(),
                                null));
                        break;
                    }
                }
            }
            for (LuaAstNode ch : n.children()) {
                analyzeAst(ch, es, out, insideLoop, insideTonumber);
            }
            return;
        }
        if (LuaAstNode.WHILE_STATEMENT.equals(k)) {
            if (n.children().size() >= 2
                    && isInfiniteWhileCondition(n.children().get(0))
                    && !blockHasBreak(n.children().get(1))) {
                out.add(new SemanticError(
                        "LUA-002",
                        es ? "Bucle while infinito sin break detectable" : "Infinite while loop with no detectable break",
                        es ? "Añada break, condición finita o use un contador acotado."
                                : "Add a break, a finite condition, or a bounded counter.",
                        SemanticError.Severity.ERROR,
                        n.line(),
                        null));
            }
            for (LuaAstNode ch : n.children()) {
                analyzeAst(ch, es, out, true, insideTonumber);
            }
            return;
        }
        if (LuaAstNode.REPEAT_STATEMENT.equals(k) && n.children().size() >= 2) {
            LuaAstNode body = n.children().get(0);
            LuaAstNode cond = n.children().get(1);
            if (isAlwaysFalseCondition(cond) && !blockHasBreak(body)) {
                out.add(new SemanticError(
                        "LUA-002",
                        es ? "repeat until false sin break — bucle infinito" : "repeat until false with no break — infinite loop",
                        es ? "Añada break o cambie la condición de salida."
                                : "Add a break or change the exit condition.",
                        SemanticError.Severity.ERROR,
                        n.line(),
                        null));
            }
            for (LuaAstNode ch : n.children()) {
                analyzeAst(ch, es, out, true, insideTonumber);
            }
            return;
        }
        if (LuaAstNode.FOR_STATEMENT.equals(k)) {
            for (LuaAstNode ch : n.children()) {
                analyzeAst(ch, es, out, true, insideTonumber);
            }
            return;
        }
        if (LuaAstNode.IF_STATEMENT.equals(k)) {
            for (LuaAstNode ch : n.children()) {
                analyzeAst(ch, es, out, insideLoop, insideTonumber);
            }
            return;
        }
        for (LuaAstNode ch : n.children()) {
            analyzeAst(ch, es, out, insideLoop, insideTonumber);
        }
    }

    private static boolean isArithmetic(String op) {
        return "+".equals(op) || "-".equals(op) || "*".equals(op) || "/".equals(op) || "%".equals(op) || "//".equals(op);
    }

    private static boolean isTonumberCall(LuaAstNode call) {
        if (!LuaAstNode.FUNCTION_CALL.equals(call.kind()) || call.children().isEmpty()) {
            return false;
        }
        return "tonumber".equals(calleeString(call.children().get(0)));
    }

    private static boolean argvUsedWithoutTonumberWrap(LuaAstNode expr) {
        if (expr == null) {
            return false;
        }
        if (LuaAstNode.INDEX.equals(expr.kind()) && expr.children().size() >= 1) {
            LuaAstNode base = expr.children().get(0);
            if (LuaAstNode.NAME.equals(base.kind()) && "ARGV".equalsIgnoreCase(base.text())) {
                return true;
            }
        }
        if (LuaAstNode.FUNCTION_CALL.equals(expr.kind())) {
            if (isTonumberCall(expr)) {
                return false;
            }
            for (int i = 1; i < expr.children().size(); i++) {
                if (argvUsedWithoutTonumberWrap(expr.children().get(i))) {
                    return true;
                }
            }
            return false;
        }
        for (LuaAstNode ch : expr.children()) {
            if (argvUsedWithoutTonumberWrap(ch)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGlobalNameAssignment(LuaAstNode lhs) {
        return LuaAstNode.NAME.equals(lhs.kind());
    }

    private static boolean isInfiniteWhileCondition(LuaAstNode cond) {
        if (LuaAstNode.LITERAL.equals(cond.kind())) {
            String t = cond.text();
            return "true".equals(t) || "1".equals(t);
        }
        return false;
    }

    private static boolean isAlwaysFalseCondition(LuaAstNode cond) {
        if (LuaAstNode.LITERAL.equals(cond.kind())) {
            return "false".equals(cond.text());
        }
        return false;
    }

    private static boolean blockHasBreak(LuaAstNode block) {
        if (block == null) {
            return false;
        }
        if (LuaAstNode.BREAK_STATEMENT.equals(block.kind())) {
            return true;
        }
        for (LuaAstNode ch : block.children()) {
            if (blockHasBreak(ch)) {
                return true;
            }
        }
        return false;
    }

    private static String calleeString(LuaAstNode expr) {
        if (expr == null) {
            return "";
        }
        if (LuaAstNode.NAME.equals(expr.kind())) {
            return expr.text() == null ? "" : expr.text();
        }
        if (LuaAstNode.FIELD.equals(expr.kind()) && expr.children().size() >= 2) {
            return calleeString(expr.children().get(0)) + "." + expr.children().get(1).text();
        }
        if (LuaAstNode.INDEX.equals(expr.kind())) {
            return calleeString(expr.children().get(0)) + "[]";
        }
        return "";
    }

    /** LUA-004 on raw source plus numkeys. */
    public static void analyzeKeysClusterMode(String rawScript, int evalNumKeys, boolean es, List<SemanticError> out) {
        if (rawScript == null || evalNumKeys != 0) {
            return;
        }
        if (KEYS_BRACKET.matcher(rawScript).find()) {
            out.add(new SemanticError(
                    "LUA-004",
                    es ? "KEYS[] usado sin claves pasadas como parámetro (numkeys=0)" : "KEYS[] referenced with zero keys passed (numkeys=0)",
                    es ? "Romperá cluster mode: pase las claves en EVAL y use KEYS[1..n]."
                            : "Will break cluster mode: pass keys into EVAL and use KEYS[1..n].",
                    SemanticError.Severity.WARNING));
        }
    }
}
