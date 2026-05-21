package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-008 — Detects an accidental Cartesian product caused by listing multiple
 * tables in FROM without any join condition between them.
 *
 * <p>A Cartesian product multiplies every row of one table with every row of the
 * other: 1 000 rows × 1 000 rows = 1 000 000 result rows.
 *
 * <p>Differs from {@link ImplicitJoinRule} (OPT-004): ImplicitJoinRule fires when
 * there IS a cross-table equi-join condition in WHERE (just written in SQL-89 style).
 * This rule fires when there is NO such condition — the product is unintentional.
 *
 * <p>Impact: HIGH — query result set grows exponentially with table size.
 */
public class CartesianProductRule implements OptimizationRule {

    /** FROM followed by at least two comma-separated table names (with optional aliases). */
    private static final Pattern MULTI_TABLE_FROM = Pattern.compile(
            "FROM\\s+\\w+(?:\\s+(?:AS\\s+)?\\w+)?\\s*,",
            Pattern.CASE_INSENSITIVE
    );

    /** An explicit cross-table join predicate such as t1.col = t2.col. */
    private static final Pattern CROSS_TABLE_JOIN = Pattern.compile(
            "\\w+\\.\\w+\\s*=\\s*\\w+\\.\\w+",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public String getRuleId() {
        return "OPT-008";
    }

    @Override
    public String getDescription() {
        return "Cartesian product — multiple tables in FROM with no join condition";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        // Must have comma-separated tables in FROM
        if (!MULTI_TABLE_FROM.matcher(query).find()) return false;
        // Must NOT have a cross-table join condition (that case is handled by OPT-004)
        return !CROSS_TABLE_JOIN.matcher(query).find();
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "Multiple tables in FROM without a join condition produce a Cartesian product: " +
                "every row from the first table is combined with every row from the second. " +
                "Result set size = rows(t1) × rows(t2). Add an explicit JOIN with an ON condition " +
                "or move to explicit JOIN syntax.",
                "FROM table1, table2",
                "FROM table1 INNER JOIN table2 ON table1.id = table2.foreign_key",
                "HIGH"
        );
    }
}
