package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;

import java.util.regex.Pattern;

/**
 * OPT-010 — Detects ORDER BY clauses inside subqueries that are used as the
 * source of an IN, EXISTS, or derived-table expression.
 *
 * <p>Sorting inside a subquery has no effect on the final result order and causes
 * unnecessary CPU and memory overhead. Only the outermost ORDER BY is meaningful.
 *
 * <p>Impact: LOW — overhead is proportional to the subquery result size but rarely
 * changes functional behaviour for the caller.
 */
public class RedundantOrderByRule implements OptimizationRule {

    /**
     * Matches ORDER BY appearing inside a parenthesised SELECT block.
     * Uses DOTALL so {@code .} matches newlines in multi-line queries.
     */
    private static final Pattern ORDER_BY_IN_SUBQUERY = Pattern.compile(
            "\\(\\s*SELECT\\b[^)]*\\bORDER\\s+BY\\b[^)]*\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    @Override
    public String getRuleId() {
        return "OPT-010";
    }

    @Override
    public String getDescription() {
        return "ORDER BY inside a subquery is redundant — remove it to avoid unnecessary sorting";
    }

    @Override
    public boolean applies(AstNode ast, String query) {
        // The parser represents subquery content as opaque SUBQUERY nodes, so we rely
        // on the raw query for detection.
        return ORDER_BY_IN_SUBQUERY.matcher(query).find();
    }

    @Override
    public OptimizationSuggestion apply(AstNode ast, String query) {
        return new OptimizationSuggestion(
                getRuleId(),
                "An ORDER BY inside a subquery used in IN/EXISTS/derived-table has no effect on " +
                "the final result order and forces the database to sort an intermediate result set. " +
                "Remove it — only the outermost ORDER BY determines output order.",
                "(SELECT col FROM table ORDER BY col)",
                "(SELECT col FROM table)",
                "LOW"
        );
    }
}
