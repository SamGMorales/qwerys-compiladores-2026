package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-009 — Detects {@code NOT IN (SELECT ...)} and suggests rewriting as
 * {@code NOT EXISTS (...)} for correctness and performance.
 *
 * <p>Two problems with NOT IN + subquery:
 * <ol>
 *   <li><b>NULL trap</b>: if the subquery returns even a single NULL, the entire
 *       outer query returns 0 rows — a silent correctness bug.</li>
 *   <li><b>Performance</b>: NOT EXISTS can short-circuit on the first match and
 *       is more likely to be optimized into an anti-join by modern planners.</li>
 * </ol>
 *
 * <p>Impact: MEDIUM — most noticeable on large subquery result sets.
 */
public class NotInSubqueryRule implements OptimizationRule {

    private static final Pattern NOT_IN_SUBQUERY = Pattern.compile(
            "\\bNOT\\s+IN\\s*\\(\\s*SELECT\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getRuleId() {
        return "OPT-009";
    }

    @Override
    public String getDescription() {
        return "NOT IN with subquery — prefer NOT EXISTS to avoid NULL traps and improve performance";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        // Also cross-check AST: NOT_IN_EXPR node that contains a SUBQUERY child
        boolean astDetected = false;
        for (AstNode notIn : AstUtils.findNodes(ast, "NOT_IN_EXPR")) {
            if (AstUtils.hasNodeType(notIn, "SUBQUERY")) {
                astDetected = true;
                break;
            }
        }
        return astDetected || NOT_IN_SUBQUERY.matcher(query).find();
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "NOT IN with a subquery has two pitfalls: (1) if the subquery returns any NULL, " +
                "the outer query silently returns 0 rows; (2) NOT EXISTS is typically optimised " +
                "into an anti-join by the planner and short-circuits on the first match.",
                "WHERE id NOT IN (SELECT id FROM other_table WHERE condition)",
                "WHERE NOT EXISTS (SELECT 1 FROM other_table WHERE other_table.id = main_table.id AND condition)",
                "MEDIUM"
        );
    }
}
