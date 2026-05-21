package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-PROC-002 — Row-at-a-time reads inside loops (explicit FETCH, cursor FOR loops, or SELECT … INTO)
 * scale poorly compared to bulk APIs.
 */
public class RowByRowProcessingRule implements OptimizationRule {

    private static final Pattern SELECT_INTO = Pattern.compile("(?is)^SELECT\\s+.+\\bINTO\\b");

    @Override
    public String getRuleId() {
        return "OPT-PROC-002";
    }

    @Override
    public String getDescription() {
        return "Row-by-row reads in a loop — prefer bulk fetch or batch operations";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        for (AstNode loop : ProceduralOptimizationSupport.allLoops(ast)) {
            if (AstUtils.hasNodeType(loop, "FETCH_STATEMENT")) {
                return true;
            }
            if (ProceduralOptimizationSupport.forLoopHasCursorDriver(loop)) {
                return true;
            }
            for (String expr : ProceduralOptimizationSupport.rawExpressionsInLoopBodySkippingForCursor(loop)) {
                String trimmed = expr.stripLeading();
                if (trimmed.length() < 8) {
                    continue;
                }
                if (trimmed.regionMatches(true, 0, "SELECT", 0, 6)
                        && SELECT_INTO.matcher(trimmed).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "This loop reads rows one at a time (FETCH, cursor FOR loop, or SELECT … INTO per iteration). "
                        + "Bulk patterns reduce round-trips and latch contention.",
                "LOOP … FETCH / SELECT … INTO … FROM … WHERE pk = :key;",
                "Oracle: BULK COLLECT + FORALL; SQL Server: table-valued parameters / set-based temp tables; "
                        + "PostgreSQL: arrays + unnest or RETURN QUERY batches; MySQL: fewer round-trips via joins.",
                "HIGH");
    }
}
