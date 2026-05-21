package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-PROC-005 — COMMIT or ROLLBACK inside a loop forces transaction boundaries per iteration,
 * which is costly and can defeat batching.
 */
public class CommitInLoopRule implements OptimizationRule {

    private static final Pattern RAW_COMMIT = Pattern.compile("(?is)\\bCOMMIT\\b");

    /** ROLLBACK excluding {@code ROLLBACK TO SAVEPOINT}. */
    private static final Pattern RAW_ROLLBACK_WORK = Pattern.compile(
            "(?is)\\bROLLBACK\\b(?!\\s+TO\\s+SAVEPOINT\\b)");

    @Override
    public String getRuleId() {
        return "OPT-PROC-005";
    }

    @Override
    public String getDescription() {
        return "COMMIT or ROLLBACK inside a loop — batch transactions instead";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        for (AstNode loop : ProceduralOptimizationSupport.allLoops(ast)) {
            if (!AstUtils.findNodes(loop, "COMMIT_STATEMENT").isEmpty()) {
                return true;
            }
            for (AstNode rb : AstUtils.findNodes(loop, "ROLLBACK_STATEMENT")) {
                if (!ProceduralOptimizationSupport.rollbackAstIsToSavepoint(rb)) {
                    return true;
                }
            }
            for (String expr : ProceduralOptimizationSupport.allRawExpressions(loop)) {
                if (RAW_COMMIT.matcher(expr).find() || RAW_ROLLBACK_WORK.matcher(expr).find()) {
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
                "COMMIT or ROLLBACK inside a loop executes a full transaction boundary every iteration — "
                        + "expensive fsync/log traffic and higher lock churn. Prefer batching work and committing once, "
                        + "or committing on meaningful chunks.",
                "LOOP … UPDATE …; COMMIT; END LOOP;",
                "LOOP … UPDATE …; END LOOP; COMMIT;   -- or partition into batches with COMMIT every N rows",
                "HIGH");
    }
}
