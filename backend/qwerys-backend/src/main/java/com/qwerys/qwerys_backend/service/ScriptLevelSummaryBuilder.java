package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.model.AnalysisError;
import com.qwerys.qwerys_backend.model.AnalysisWarning;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the whole-script ("script level") summary for multi-statement analysis: health score,
 * cross-statement findings, and a {@link QueryAnalysisResponse} suitable for a dedicated UI panel.
 *
 * <p>Per-statement results are unchanged; this layer only adds aggregate context. New engines can
 * contribute cross-script {@link SemanticError} lists from a dedicated analyzer hook.
 */
public final class ScriptLevelSummaryBuilder {

    private ScriptLevelSummaryBuilder() {
    }

    /**
     * @param fullScript          original user input
     * @param databaseType        engine id (reserved for per-engine script rules)
     * @param statementTexts      split fragments (reserved for future rules)
     * @param statementResults    one {@link QueryAnalysisResponse} per fragment
     * @param ui                  UI locale (reserved for localized script messages)
     * @param crossScriptFindings optional engine-specific cross-script semantic findings (e.g. MongoDB)
     */
    public static BuiltScriptLevel build(
            String fullScript,
            @SuppressWarnings("unused") String databaseType,
            @SuppressWarnings("unused") List<String> statementTexts,
            List<QueryAnalysisResponse> statementResults,
            @SuppressWarnings("unused") Locale ui,
            List<SemanticError> crossScriptFindings) {
        List<AnalysisError> scriptErrors = new ArrayList<>();
        List<AnalysisWarning> scriptWarnings = new ArrayList<>();

        int blockingStmt = 0;
        if (statementResults != null) {
            for (QueryAnalysisResponse r : statementResults) {
                if (!r.isValid()) {
                    blockingStmt++;
                }
            }
        }

        if (crossScriptFindings != null) {
            for (SemanticError e : crossScriptFindings) {
                if (e.severity() == SemanticError.Severity.ERROR) {
                    scriptErrors.add(QueryAnalysisService.toApiError(e));
                } else {
                    scriptWarnings.add(new AnalysisWarning(e.code(), e.severity().name()));
                }
            }
        }

        if (statementResults != null && statementResults.size() > 1 && blockingStmt >= 2) {
            scriptWarnings.add(new AnalysisWarning("SCR-MULTI-001", SemanticError.Severity.WARNING.name()));
        }

        int productionWarns = countProductionWarnings(scriptWarnings);
        int health = computeHealthPercent(statementResults, scriptErrors.size(), productionWarns);
        boolean scriptValid = blockingStmt == 0 && scriptErrors.isEmpty();

        String analyzed = fullScript != null ? fullScript : "";
        QueryAnalysisResponse summary = new QueryAnalysisResponse(
                scriptValid,
                scriptErrors,
                scriptWarnings,
                List.of(),
                analyzed,
                0L);
        return new BuiltScriptLevel(summary, health);
    }

    /**
     * Weighted health: averages per-statement band, then subtracts cross-script noise.
     */
    public static int computeHealthPercent(
            List<QueryAnalysisResponse> statementResults,
            int crossErrors,
            int crossWarnings) {
        if (statementResults == null || statementResults.isEmpty()) {
            return crossErrors > 0 ? 0 : 100;
        }
        int n = statementResults.size();
        double sum = 0;
        for (QueryAnalysisResponse r : statementResults) {
            if (!r.isValid()) {
                sum += 25;
            } else if (r.errors() != null && !r.errors().isEmpty()) {
                sum += 35;
            } else if (hasProductionWarnings(r.warnings())) {
                sum += 78;
            } else {
                sum += 100;
            }
        }
        int base = (int) Math.round(sum / n);
        int penalty = crossErrors * 18 + crossWarnings * 7;
        int score = base - penalty;
        return Math.max(0, Math.min(100, score));
    }

    private static boolean hasProductionWarnings(List<AnalysisWarning> warnings) {
        return warnings != null && warnings.stream().anyMatch(AnalysisWarning::productionRisk);
    }

    private static int countProductionWarnings(List<AnalysisWarning> warnings) {
        if (warnings == null) {
            return 0;
        }
        return (int) warnings.stream().filter(AnalysisWarning::productionRisk).count();
    }

    public record BuiltScriptLevel(QueryAnalysisResponse summary, int healthPercent) {}
}
