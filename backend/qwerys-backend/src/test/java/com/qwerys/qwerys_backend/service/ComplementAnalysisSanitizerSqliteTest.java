package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisRequest;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisResponse;
import com.qwerys.qwerys_backend.model.ai.OptimizationDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the dialect-mismatch filter now also protects SQLite, which previously
 * fell through to the default {@code -> false} branch and let the AI emit FETCH FIRST /
 * TOP / WITH (INDEX) / PIVOT / RIGHT JOIN suggestions for a SQLite query.
 */
class ComplementAnalysisSanitizerSqliteTest {

    private static ComplementAnalysisRequest sqliteRequest() {
        return new ComplementAnalysisRequest(
                "SELECT * FROM users LIMIT 10",
                "sqlite",
                "en",
                true,
                false,
                "sql",
                "sqlite",
                null,
                null,
                List.of(),
                List.of(),
                List.of(), null, null, null, null, null);
    }

    private static ComplementAnalysisResponse buildResponseWith(OptimizationDto opt) {
        return ComplementAnalysisResponse.ok(
                "pedagogy",
                "",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(opt),
                List.of(),
                null,
                true,
                "test",
                0L);
    }

    @Test
    void fetchFirstSuggestionIsFilteredForSqlite() {
        OptimizationDto bad = new OptimizationDto(
                "AI-OPT-1",
                "MEDIUM",
                "Use FETCH FIRST for pagination",
                "LIMIT 10",
                "FETCH FIRST 10 ROWS ONLY");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(buildResponseWith(bad), sqliteRequest());
        assertEquals(0, sanitized.additionalOptimizations().size(),
                "FETCH FIRST is not valid SQLite syntax — must be filtered out");
    }

    @Test
    void topSuggestionIsFilteredForSqlite() {
        OptimizationDto bad = new OptimizationDto(
                "AI-OPT-2",
                "LOW",
                "Use TOP instead of LIMIT",
                "LIMIT 5",
                "SELECT TOP 5 * FROM users");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(buildResponseWith(bad), sqliteRequest());
        assertEquals(0, sanitized.additionalOptimizations().size(),
                "T-SQL TOP must not leak into SQLite suggestions");
    }

    @Test
    void rightJoinSuggestionIsFilteredForSqlite() {
        OptimizationDto bad = new OptimizationDto(
                "AI-OPT-3",
                "MEDIUM",
                "Rewrite as RIGHT JOIN",
                "LEFT JOIN orders o ON o.user_id = u.id",
                "FROM orders o RIGHT JOIN users u ON u.id = o.user_id");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(buildResponseWith(bad), sqliteRequest());
        assertEquals(0, sanitized.additionalOptimizations().size(),
                "SQLite does not support RIGHT JOIN — suggestion must be filtered");
    }

    @Test
    void withIndexHintIsFilteredForSqlite() {
        OptimizationDto bad = new OptimizationDto(
                "AI-OPT-4",
                "HIGH",
                "Force index usage",
                "FROM users",
                "FROM users WITH (INDEX = idx_users_email)");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(buildResponseWith(bad), sqliteRequest());
        assertEquals(0, sanitized.additionalOptimizations().size(),
                "SQL Server WITH (INDEX) hints must not be suggested for SQLite");
    }

    @Test
    void validSqliteOptimizationSurvives() {
        OptimizationDto good = new OptimizationDto(
                "AI-OPT-5",
                "MEDIUM",
                "Add ORDER BY to make pagination deterministic",
                "LIMIT 10",
                "ORDER BY id LIMIT 10");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(buildResponseWith(good), sqliteRequest());
        assertEquals(1, sanitized.additionalOptimizations().size(),
                "A genuinely SQLite-valid optimization must NOT be filtered");
        assertTrue(sanitized.additionalOptimizations().get(0)
                .optimizedFragment().contains("ORDER BY"));
    }

    @Test
    void preExistingDialectsStillBlocked() {
        // Sanity: changes for SQLite must not loosen the other dialect filters.
        ComplementAnalysisRequest oracleReq = new ComplementAnalysisRequest(
                "SELECT * FROM users",
                "oracle",
                "en",
                true,
                false,
                "sql",
                "oracle",
                null,
                null,
                List.of(),
                List.of(),
                List.of(), null, null, null, null, null);
        OptimizationDto bad = new OptimizationDto(
                "AI-OPT-6",
                "LOW",
                "Use LIMIT for Oracle",
                "ROWNUM <= 10",
                "SELECT * FROM users LIMIT 10");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(buildResponseWith(bad), oracleReq);
        assertFalse(sanitized.additionalOptimizations().stream()
                .anyMatch(o -> o.optimizedFragment().contains(" LIMIT ")),
                "LIMIT is not valid Oracle — filter must still apply");
    }
}
