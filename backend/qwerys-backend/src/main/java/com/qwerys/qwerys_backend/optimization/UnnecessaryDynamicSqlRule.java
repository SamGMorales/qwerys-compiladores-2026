package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OPT-PROC-004 — Dynamic EXECUTE/EXEC wrapping a fixed SELECT literal adds parsing overhead without
 * flexibility when nothing is concatenated or bound.
 */
public class UnnecessaryDynamicSqlRule implements OptimizationRule {

    /** {@code EXECUTE [IMMEDIATE] 'SELECT …'} without trailing USING / INTO clause on same fragment. */
    private static final Pattern EXECUTE_SINGLE_QUOTED_SELECT = Pattern.compile(
            "(?is)\\bEXEC(?:UTE)?(?:\\s+IMMEDIATE)?\\s+'((?:[^']|'')*\\bSELECT\\b(?:[^']|'')*)'(?:\\s|;|$)+");

    /** {@code EXEC(N'SELECT …')} / {@code EXEC('SELECT …')} */
    private static final Pattern EXEC_PAREN_SELECT = Pattern.compile(
            "(?is)\\bEXEC\\s*\\(\\s*N?'((?:[^']|'')*\\bSELECT\\b(?:[^']|'')*)'\\s*\\)");

    private static final Pattern BIND_OR_VAR = Pattern.compile(
            "(?is)@\\w+|\\?|\\$\\d+|(?<!:):\\s*[a-z_][a-z0-9_]*\\b");

    @Override
    public String getRuleId() {
        return "OPT-PROC-004";
    }

    @Override
    public String getDescription() {
        return "EXECUTE of a fixed SELECT literal — use static SQL";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return matchesStaticExecute(query, EXECUTE_SINGLE_QUOTED_SELECT)
                || matchesStaticExecute(query, EXEC_PAREN_SELECT)
                || astHasStaticExecute(ast);
    }

    private static boolean matchesStaticExecute(String query, Pattern p) {
        Matcher m = p.matcher(query);
        while (m.find()) {
            String inner = m.group(1).replace("''", "'");
            if (looksLikeConstantSelect(inner)) {
                return true;
            }
        }
        return false;
    }

    private static boolean astHasStaticExecute(AstNode root) {
        if ("EXECUTE_STATEMENT".equals(root.getNodeType()) && root.getChildren() != null) {
            for (AstNode ch : root.getChildren()) {
                if ("EXPRESSION".equals(ch.getNodeType()) && ch.getValue() != null) {
                    String v = ch.getValue().trim();
                    Matcher m = EXECUTE_SINGLE_QUOTED_SELECT.matcher(v);
                    if (m.find() && looksLikeConstantSelect(m.group(1).replace("''", "'"))) {
                        return true;
                    }
                }
            }
        }
        for (AstNode c : root.getChildren()) {
            if (astHasStaticExecute(c)) {
                return true;
            }
        }
        return false;
    }

    /** No obvious host variables / binds / concatenation inside the extracted SELECT text. */
    private static boolean looksLikeConstantSelect(String inner) {
        if (inner == null || inner.isBlank()) {
            return false;
        }
        String t = inner.trim();
        if (!t.regionMatches(true, 0, "SELECT", 0, 6)) {
            return false;
        }
        return !BIND_OR_VAR.matcher(t).find() && !t.contains("||");
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "EXECUTE / EXEC wraps a SELECT that appears to be a fixed literal (no binds or concatenation). "
                        + "Static SQL is parsed once and is easier for the optimizer to plan.",
                "EXECUTE IMMEDIATE 'SELECT … FROM … WHERE …';",
                "SELECT … FROM … WHERE …;   -- or EXECUTE … USING only when parameters change per call",
                "MEDIUM");
    }
}
