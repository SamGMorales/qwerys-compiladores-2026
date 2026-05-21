package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OPT-PROC-003 — Identical static SELECT text appearing more than once inside the same loop body is
 * redundant work each iteration.
 */
public class RepeatedQueryInLoopRule implements OptimizationRule {

    @Override
    public String getRuleId() {
        return "OPT-PROC-003";
    }

    @Override
    public String getDescription() {
        return "Duplicate SELECT inside a loop — hoist invariant reads outside the loop";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        for (AstNode loop : ProceduralOptimizationSupport.allLoops(ast)) {
            List<String> selects = new ArrayList<>();
            for (String expr : ProceduralOptimizationSupport.rawExpressionsInLoopBodySkippingForCursor(loop)) {
                String t = expr.stripLeading();
                if (t.regionMatches(true, 0, "SELECT", 0, 6) && looksBindingFree(t)) {
                    selects.add(ProceduralOptimizationSupport.normalizeSqlFragment(t));
                }
            }
            if (selects.size() < 2) {
                continue;
            }
            Map<String, Integer> freq = new HashMap<>();
            for (String s : selects) {
                freq.merge(s, 1, Integer::sum);
            }
            for (int c : freq.values()) {
                if (c >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Skip obviously parameterized fragments — hoisting would change semantics.
     */
    private static boolean looksBindingFree(String sql) {
        return !sql.contains("@")
                && !sql.contains("?")
                && !sql.contains("${")
                && !CONTAINS_BIND.matcher(sql).find()
                && !sql.contains("||");
    }

    /** Bind placeholders — avoids matching PostgreSQL {@code ::type} casts. */
    private static final java.util.regex.Pattern CONTAINS_BIND =
            java.util.regex.Pattern.compile("(?is)(?<!:):\\s*[a-z_][a-z0-9_]*\\b");

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "The same SELECT text appears multiple times inside one loop iteration path. "
                        + "If its result does not depend on loop variables that change each iteration, "
                        + "execute it once before the loop and reuse the outcome.",
                "LOOP … SELECT … FROM cfg WHERE id = 1; … SELECT … FROM cfg WHERE id = 1; … END LOOP;",
                "SELECT … FROM cfg WHERE id = 1 INTO …; LOOP … -- reuse cached row(s) … END LOOP;",
                "MEDIUM");
    }
}
