package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.ai.AiClient;
import com.qwerys.qwerys_backend.ai.AiLocaleHelper;
import com.qwerys.qwerys_backend.model.AnalysisMetadata;
import com.qwerys.qwerys_backend.model.AnalysisWarning;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AI-primary analysis for user-declared custom database engines ({@code custom::…}).
 * Does not modify native parsers; uses reference-base native analysis only as context.
 */
@Service
public class CustomEngineAiAnalyzer {

    private final AiClient aiClient;
    private final AiStructuredAnalysisParser parser;
    private final QueryAnalysisService queryAnalysisService;

    public CustomEngineAiAnalyzer(
            AiClient aiClient,
            AiStructuredAnalysisParser parser,
            @Lazy QueryAnalysisService queryAnalysisService) {
        this.aiClient = aiClient;
        this.parser = parser;
        this.queryAnalysisService = queryAnalysisService;
    }

    public boolean isAvailable() {
        return aiClient.isAvailable();
    }

    public QueryAnalysisResponse analyze(
            QueryRequest request,
            boolean expertMode,
            Locale ui,
            long startNano) {
        CustomEngineContext ctx = CustomEngineContext.from(request);
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;

        AnalysisMetadata metadata = new AnalysisMetadata(
                "ai-custom",
                ctx.customName(),
                ctx.referenceBase(),
                null);

        String referenceSummary = buildReferenceSummary(request, ctx, expertMode, ui);

        String system = buildSystemPrompt(ctx, ui, expertMode);
        String user = buildUserPrompt(request, ctx, referenceSummary, ui);

        try {
            String raw = aiClient.complete(system, user);
            if (raw == null || raw.isBlank()) {
                return approximateFallback(request, ctx, ui, startNano);
            }
            return parser.parse(
                    raw,
                    request.query(),
                    elapsedMs,
                    metadata,
                    false);
        } catch (Exception ex) {
            return approximateFallback(request, ctx, ui, startNano);
        }
    }

    /**
     * When AI is unavailable: native analysis on reference base, clearly marked as approximate.
     */
    public QueryAnalysisResponse approximateFallback(
            QueryRequest request,
            CustomEngineContext ctx,
            Locale ui,
            long startNano) {
        QueryRequest baseReq = ctx.asReferenceBaseRequest(request);
        QueryAnalysisResponse base = queryAnalysisService.analyzeNativeOnly(baseReq, false);

        AnalysisMetadata metadata = new AnalysisMetadata(
                "native-approximate-custom",
                ctx.customName(),
                ctx.referenceBase(),
                null);

        // Sin chip AI-CUSTOM-APPROX: el banner (metadata) ya explica el modo aproximado.
        List<AnalysisWarning> warnings = base.warnings() != null ? base.warnings() : List.of();

        return new QueryAnalysisResponse(
                base.isValid(),
                base.errors(),
                warnings,
                base.optimizations(),
                base.analyzedQuery(),
                (System.nanoTime() - startNano) / 1_000_000,
                metadata);
    }

    private String buildReferenceSummary(
            QueryRequest request,
            CustomEngineContext ctx,
            boolean expertMode,
            Locale ui) {
        try {
            QueryAnalysisResponse ref = queryAnalysisService.analyzeNativeOnly(
                    ctx.asReferenceBaseRequest(request),
                    expertMode);
            return AiStructuredAnalysisParser.summarizeReference(ref, ui);
        } catch (Exception ex) {
            return AiLocaleHelper.isSpanish(ui)
                    ? "(No se pudo obtener análisis de referencia del motor base.)"
                    : "(Could not obtain reference analysis from the base engine.)";
        }
    }

    private static String buildSystemPrompt(CustomEngineContext ctx, Locale ui, boolean expertMode) {
        String lang = AiLocaleHelper.languageInstruction(ui);
        String expertNote = expertMode
                ? "The client may show expert panels; do not invent lexer metrics. Omit AST."
                : "Keep explanations accessible.";
        return """
                You are QWERYS, an expert database analysis engine (Grammarly for SQL/NoSQL).
                The user declared a CUSTOM database engine named "%s".
                It is SIMILAR TO (not identical to) "%s".
                %s
                %s
                Analyze the user's query for the CUSTOM engine. Do NOT assume it is exactly %s.
                Use %s only as a structural hint.
                %s
                %s
                Return ONLY valid JSON matching the schema given. No markdown outside JSON.
                """.formatted(
                ctx.customName(),
                ctx.referenceBase(),
                CustomEngineAnalysisSupport.customEngineSyntaxHint(ctx),
                lang,
                ctx.referenceBase(),
                ctx.referenceBase(),
                CustomEngineAnalysisSupport.promptAddendum(ctx, ui),
                expertNote);
    }

    private static String buildUserPrompt(
            QueryRequest request,
            CustomEngineContext ctx,
            String referenceSummary,
            Locale ui) {
        boolean es = AiLocaleHelper.isSpanish(ui);
        return """
                Custom engine: %s
                Reference engine (hint only): %s
                User query to analyze:
                %s

                Reference analysis if this were purely %s (NOT authoritative for %s):
                %s

                JSON schema (return exactly this shape):
                {
                  "isValid": boolean,
                  "analyzedQuery": string,
                  "errors": [
                    {
                      "code": string,
                      "message": string,
                      "suggestion": string,
                      "line": number or null,
                      "column": number or null,
                      "education": {
                        "what": string,
                        "why": string,
                        "example": string,
                        "correctedExample": string
                      }
                    }
                  ],
                  "warnings": [ { "code": string, "severity": "WARNING" | "INFO" } ],
                  "optimizations": [
                    {
                      "ruleId": string,
                      "description": string,
                      "originalFragment": string,
                      "optimizedFragment": string,
                      "impact": "HIGH" | "MEDIUM" | "LOW"
                    }
                  ],
                  "referenceComparison": string
                }

                %s
                """.formatted(
                ctx.customName(),
                ctx.referenceBase(),
                request.query(),
                ctx.referenceBase(),
                ctx.customName(),
                referenceSummary,
                es
                        ? "Incluye referenceComparison explicando diferencias entre el análisis de referencia y lo que aplica a "
                                + ctx.customName() + "."
                        : "Include referenceComparison explaining differences between the reference analysis and what applies to "
                                + ctx.customName() + ".");
    }
}
