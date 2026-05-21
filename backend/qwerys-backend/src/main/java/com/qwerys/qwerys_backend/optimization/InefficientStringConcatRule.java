package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-PROC-006 — Repeated {@code ||} / {@code CONCAT} inside a loop reallocates intermediate strings;
 * procedural languages offer bounded builders or XML/JSON aggregates for the same outcome.
 */
public class InefficientStringConcatRule implements OptimizationRule {

    private static final Pattern LOOP_CONCAT = Pattern.compile("\\|\\|");

    private static final Pattern LOOP_CONCAT_FUNC = Pattern.compile("(?is)\\bCONCAT\\s*\\(");

    @Override
    public String getRuleId() {
        return "OPT-PROC-006";
    }

    @Override
    public String getDescription() {
        return "String concatenation inside a loop — prefer a builder or aggregate API";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        for (AstNode loop : ProceduralOptimizationSupport.allLoops(ast)) {
            if (loopSubtreeHasConcatAssignment(loop)) {
                return true;
            }
        }
        return false;
    }

    private static boolean loopSubtreeHasConcatAssignment(AstNode node) {
        if ("ASSIGNMENT_STATEMENT".equals(node.getNodeType())
                || "SET_STATEMENT".equals(node.getNodeType())) {
            for (AstNode ch : node.getChildren()) {
                if ("EXPRESSION".equals(ch.getNodeType())
                        && ch.getValue() != null
                        && (LOOP_CONCAT.matcher(ch.getValue()).find()
                                || LOOP_CONCAT_FUNC.matcher(ch.getValue()).find())) {
                    return true;
                }
            }
        }
        if ("RAW_STATEMENT".equals(node.getNodeType())) {
            for (AstNode ch : node.getChildren()) {
                if ("EXPRESSION".equals(ch.getNodeType()) && ch.getValue() != null) {
                    String v = ch.getValue();
                    if ((LOOP_CONCAT.matcher(v).find() || LOOP_CONCAT_FUNC.matcher(v).find())
                            && ASSIGN_CONCAT.matcher(v).find()) {
                        return true;
                    }
                }
            }
        }
        for (AstNode c : node.getChildren()) {
            if (loopSubtreeHasConcatAssignment(c)) {
                return true;
            }
        }
        return false;
    }

    /** Assignment-shaped SQL ({@code SET x =} / {@code :=}) so plain predicates with {@code ||} do not fire. */
    private static final Pattern ASSIGN_CONCAT = Pattern.compile(
            "(?is)(?:\\bSET\\s+\\S+\\s*=|\\w+\\s*:=)");

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "Concatenating strings with || or CONCAT inside a loop grows allocations quadratically when building "
                        + "large payloads. Use the procedural language's string builder, LISTAGG/XMLAGG/STRING_AGG, "
                        + "or SQL Server's CONCAT_WS over a set.",
                "LOOP … x := x || chunk; … END LOOP;",
                "Oracle: XMLAGG / LISTAGG; SQL Server: STRING_AGG or StringBuilder via CLR-safe patterns; "
                        + "PostgreSQL: string_agg / array_to_string; MySQL: GROUP_CONCAT for batch builds.",
                "MEDIUM");
    }
}
