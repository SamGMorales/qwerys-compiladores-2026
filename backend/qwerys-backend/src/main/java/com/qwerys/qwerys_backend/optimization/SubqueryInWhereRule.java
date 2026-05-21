package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-005 — Detects correlated or uncorrelated subqueries inside a WHERE IN(...)
 * clause and suggests replacing them with a JOIN for better performance.
 *
 * <p>Impact: HIGH — subqueries in WHERE IN are often evaluated row-by-row;
 * a JOIN allows the optimizer to choose a more efficient execution plan such as
 * a hash join or merge join.
 */
public class SubqueryInWhereRule implements OptimizationRule {

    /** WHERE ... IN (SELECT ...) */
    private static final Pattern WHERE_IN_SUBQUERY = Pattern.compile(
            "\\bIN\\s*\\(\\s*SELECT\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** WHERE EXISTS (SELECT ...) — also benefits from JOIN rewrite in many cases */
    private static final Pattern WHERE_EXISTS_SUBQUERY = Pattern.compile(
            "\\bEXISTS\\s*\\(\\s*SELECT\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getRuleId() {
        return "OPT-005";
    }

    @Override
    public String getDescription() {
        return "Subquery in WHERE IN(...) — consider rewriting as a JOIN";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        // Check AST: a SUBQUERY node exists inside a WHERE_CLAUSE
        boolean astDetected = AstUtils.hasNodeType(ast, "WHERE_CLAUSE")
                && AstUtils.hasNodeType(ast, "SUBQUERY");

        // Fallback: raw query regex (catches cases where parser uses placeholder nodes)
        boolean regexDetected = WHERE_IN_SUBQUERY.matcher(query).find()
                || WHERE_EXISTS_SUBQUERY.matcher(query).find();

        return astDetected || regexDetected;
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "A subquery inside WHERE IN(...) is often executed once per outer row. " +
                "Replacing it with an INNER JOIN lets the optimizer pick a hash or merge join " +
                "strategy, which is typically far more efficient on large data sets.",
                "WHERE id IN (SELECT id FROM other_table WHERE condition)",
                "INNER JOIN other_table ON main_table.id = other_table.id WHERE condition",
                "HIGH"
        );
    }
}
