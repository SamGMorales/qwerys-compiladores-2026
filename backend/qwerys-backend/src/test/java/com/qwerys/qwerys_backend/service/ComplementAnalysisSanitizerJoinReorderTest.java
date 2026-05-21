package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisRequest;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisResponse;
import com.qwerys.qwerys_backend.model.ai.OptimizationDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ComplementAnalysisSanitizerJoinReorderTest {

    private static ComplementAnalysisRequest mysqlRequest() {
        return new ComplementAnalysisRequest(
                "SELECT u.id FROM usuarios u LEFT JOIN pedidos o ON o.user_id = u.id",
                "mysql",
                "es",
                true,
                false,
                "sql",
                "mysql",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }

    private static ComplementAnalysisResponse responseWith(OptimizationDto opt) {
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
    void filtersUnsafeLeftJoinTableSwap() {
        OptimizationDto bad = new OptimizationDto(
                "AI-OPT-JOIN",
                "MEDIUM",
                "Reordenar tablas para mejorar rendimiento",
                "FROM usuarios u LEFT JOIN pedidos o ON o.user_id = u.id",
                "FROM pedidos o LEFT JOIN usuarios u ON o.user_id = u.id");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(responseWith(bad), mysqlRequest());
        assertEquals(0, sanitized.additionalOptimizations().size());
    }

    @Test
    void keepsInnerJoinRewriteFromSubquery() {
        OptimizationDto good = new OptimizationDto(
                "AI-OPT-005",
                "HIGH",
                "Subquery to join",
                "WHERE id IN (SELECT id FROM other_table WHERE x = 1)",
                "INNER JOIN other_table ON main_table.id = other_table.id WHERE x = 1");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(responseWith(good), mysqlRequest());
        assertEquals(1, sanitized.additionalOptimizations().size());
    }

    @Test
    void keepsSqliteStyleRightToLeftRewrite() {
        ComplementAnalysisRequest sqliteReq = new ComplementAnalysisRequest(
                "SELECT * FROM A RIGHT JOIN B ON A.id = B.id",
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
                List.of(),
                null,
                null,
                null,
                null,
                null);
        OptimizationDto good = new OptimizationDto(
                "AI-OPT-SQLITE",
                "MEDIUM",
                "SQLite RIGHT to LEFT",
                "FROM A RIGHT JOIN B ON A.id = B.id",
                "FROM B LEFT JOIN A ON A.id = B.id");
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(responseWith(good), sqliteReq);
        assertEquals(1, sanitized.additionalOptimizations().size());
    }

    @Test
    void keepsUnchangedLeftJoin() {
        String fragment = "FROM usuarios u LEFT JOIN pedidos o ON o.user_id = u.id";
        OptimizationDto good = new OptimizationDto(
                "AI-OPT-IDX",
                "MEDIUM",
                "Add index",
                fragment,
                "CREATE INDEX idx_user ON pedidos(user_id); " + fragment);
        ComplementAnalysisResponse sanitized =
                ComplementAnalysisSanitizer.sanitize(responseWith(good), mysqlRequest());
        assertFalse(sanitized.additionalOptimizations().isEmpty());
    }
}
