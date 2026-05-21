package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-004 — Detects SQL-89 style implicit JOINs where multiple tables are listed
 * in the FROM clause separated by commas and joined via a WHERE condition
 * (e.g. {@code FROM t1, t2 WHERE t1.id = t2.fk}).
 *
 * <p>Implicit JOINs are valid but reduce readability and make it easy to accidentally
 * produce a Cartesian product if the WHERE condition is later removed or forgotten.
 *
 * <p>Impact: MEDIUM — functionality is equivalent but explicit JOIN syntax is safer
 * and easier for the optimizer to reason about.
 */
public class ImplicitJoinRule implements OptimizationRule {

    /** Matches FROM followed by at least two comma-separated table names (with optional aliases). */
    private static final Pattern MULTI_TABLE_FROM = Pattern.compile(
            "FROM\\s+\\w+(?:\\s+(?:AS\\s+)?\\w+)?\\s*,",
            Pattern.CASE_INSENSITIVE
    );

    /** Matches a cross-table equi-join condition of the form alias.col = alias.col. */
    private static final Pattern CROSS_TABLE_JOIN = Pattern.compile(
            "\\w+\\.\\w+\\s*=\\s*\\w+\\.\\w+",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getRuleId() {
        return "OPT-004";
    }

    @Override
    public String getDescription() {
        return "Implicit JOIN in FROM clause — use explicit JOIN ... ON syntax";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        // Must have comma-separated tables in FROM
        if (!MULTI_TABLE_FROM.matcher(query).find()) return false;
        // AND a cross-table equi-join condition (otherwise it's a Cartesian product, caught by OPT-008)
        return CROSS_TABLE_JOIN.matcher(query).find();
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "Comma-separated tables in FROM with a WHERE join condition is SQL-89 (implicit JOIN) syntax. " +
                "Replacing it with explicit INNER JOIN ... ON improves readability, prevents accidental " +
                "Cartesian products, and is the modern SQL standard.",
                "FROM table1, table2 WHERE table1.id = table2.fk",
                "FROM table1 INNER JOIN table2 ON table1.id = table2.fk",
                "MEDIUM"
        );
    }
}
