package com.qwerys.qwerys_backend.student;

import com.qwerys.qwerys_backend.dto.StudentExplanationDto;
import com.qwerys.qwerys_backend.model.AnalysisError;
import com.qwerys.qwerys_backend.model.MultiStatementAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adds optional {@link StudentExplanationDto} payloads to analysis errors when student mode is on.
 * Does not alter analysis logic, messages, or suggestions — only augments the API response.
 */
@Component
public class StudentExplanationEnricher {

    private final StudentExplanationRegistry registry;

    public StudentExplanationEnricher(StudentExplanationRegistry registry) {
        this.registry = registry;
    }

    public static Locale resolveUiLocale(QueryRequest request) {
        String tag = request.locale();
        if (tag == null || tag.isBlank()) {
            return Locale.ENGLISH;
        }
        return Locale.forLanguageTag(tag.trim().replace('_', '-'));
    }

    public QueryAnalysisResponse enrich(QueryAnalysisResponse response, Locale locale) {
        if (response == null || response.errors() == null || response.errors().isEmpty()) {
            return response;
        }
        List<AnalysisError> enriched = new ArrayList<>(response.errors().size());
        boolean changed = false;
        for (AnalysisError error : response.errors()) {
            AnalysisError withEducation = attachEducation(error, locale);
            enriched.add(withEducation);
            if (withEducation != error) {
                changed = true;
            }
        }
        if (!changed) {
            return response;
        }
        return new QueryAnalysisResponse(
                response.isValid(),
                enriched,
                response.warnings(),
                response.optimizations(),
                response.analyzedQuery(),
                response.executionTimeMs(),
                response.astTree(),
                response.metrics(),
                response.metadata(),
                response.historyEntryId());
    }

    public MultiStatementAnalysisResponse enrich(MultiStatementAnalysisResponse response, Locale locale) {
        if (response == null) {
            return null;
        }
        List<QueryAnalysisResponse> statements = response.statements() == null
                ? List.of()
                : response.statements().stream().map(r -> enrich(r, locale)).toList();
        QueryAnalysisResponse scriptLevel = response.scriptLevel() != null
                ? enrich(response.scriptLevel(), locale)
                : null;
        return new MultiStatementAnalysisResponse(
                statements,
                response.totalExecutionTimeMs(),
                scriptLevel,
                response.scriptHealthPercent(),
                response.historyEntryId());
    }

    private AnalysisError attachEducation(AnalysisError error, Locale locale) {
        if (error == null || error.education() != null) {
            return error;
        }
        StudentExplanationDto education = registry.lookup(error.code(), locale);
        if (education == null) {
            return error;
        }
        return new AnalysisError(
                error.code(),
                error.message(),
                error.suggestion(),
                error.line(),
                error.column(),
                education);
    }
}
