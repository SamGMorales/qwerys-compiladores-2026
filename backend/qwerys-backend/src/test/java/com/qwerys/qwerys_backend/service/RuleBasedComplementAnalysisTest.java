package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.ai.AnalysisErrorDto;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisRequest;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedComplementAnalysisTest {

    private final RuleBasedAiFallback fallback = new RuleBasedAiFallback();

    @Test
    void detectsWherfTypoAndSyntaxCorrection() {
        String query = "SELECT id FROM employees WHERF department_id = 10";
        ComplementAnalysisRequest req = new ComplementAnalysisRequest(
                query,
                "oracle",
                "en",
                false,
                false,
                null,
                null,
                null,
                null,
                List.of(new AnalysisErrorDto("SYN-001-SQL", "syntax", "fix")),
                List.of(),
                List.of(), null, null, null, null, null);

        ComplementAnalysisResponse res = fallback.complementAnalysisStructured(req, 1);
        assertTrue(res.additionalErrors().stream().anyMatch(e -> "AI-ERR-TYPO-WHERE".equals(e.code())));
        assertTrue(res.syntaxCorrections().stream()
                .anyMatch(s -> s.correctedQuery().contains("WHERE")));
    }

    @Test
    void flagsIncompletePasteStartingWithFrom() {
        String query = "FROM employees e\nINNER JOIN dept d ON d.id = e.dept_id";
        ComplementAnalysisRequest req = new ComplementAnalysisRequest(
                query,
                "postgresql",
                "es",
                false,
                false,
                null,
                null,
                null,
                null,
                List.of(new AnalysisErrorDto("SYN-001-SQL", "syntax", "fix")),
                List.of(),
                List.of(), null, null, null, null, null);

        ComplementAnalysisResponse res = fallback.complementAnalysisStructured(req, 1);
        assertTrue(res.additionalWarnings().stream().anyMatch(w -> "AI-WARN-INCOMPLETE".equals(w.code())));
        assertFalse(res.nativeReviews().isEmpty());
    }

}
