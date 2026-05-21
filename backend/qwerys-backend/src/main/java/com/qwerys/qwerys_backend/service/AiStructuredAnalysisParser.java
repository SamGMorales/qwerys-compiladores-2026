package com.qwerys.qwerys_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerys.qwerys_backend.dto.StudentExplanationDto;
import com.qwerys.qwerys_backend.model.AnalysisError;
import com.qwerys.qwerys_backend.model.AnalysisWarning;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.AnalysisMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AiStructuredAnalysisParser {

    private final ObjectMapper objectMapper;

    public AiStructuredAnalysisParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public QueryAnalysisResponse parse(
            String rawJson,
            String fallbackQuery,
            long elapsedMs,
            AnalysisMetadata metadata,
            boolean includeExpertFields) {
        try {
            String cleaned = stripMarkdownFences(rawJson);
            JsonNode root = objectMapper.readTree(cleaned);
            boolean isValid = root.path("isValid").asBoolean(true);
            String analyzedQuery = textOr(root.path("analyzedQuery"), fallbackQuery);
            List<AnalysisError> errors = parseErrors(root.path("errors"));
            List<AnalysisWarning> warnings = parseWarnings(root.path("warnings"));
            List<OptimizationSuggestion> optimizations = parseOptimizations(root.path("optimizations"));

            String comparison = textOr(root.path("referenceComparison"), null);
            AnalysisMetadata meta = metadata;
            if (comparison != null && !comparison.isBlank() && meta != null) {
                meta = new AnalysisMetadata(
                        meta.source(),
                        meta.declaredEngineLabel(),
                        meta.referenceBaseEngine(),
                        comparison);
            }

            if (includeExpertFields) {
                return new QueryAnalysisResponse(
                        isValid,
                        errors,
                        warnings,
                        optimizations,
                        analyzedQuery,
                        elapsedMs,
                        null,
                        null,
                        meta,
                        null);
            }
            return new QueryAnalysisResponse(
                    isValid,
                    errors,
                    warnings,
                    optimizations,
                    analyzedQuery,
                    elapsedMs,
                    meta);
        } catch (Exception ex) {
            return fallbackParseError(fallbackQuery, elapsedMs, metadata, ex.getMessage());
        }
    }

    private static QueryAnalysisResponse fallbackParseError(
            String query,
            long elapsedMs,
            AnalysisMetadata metadata,
            String detail) {
        List<AnalysisWarning> warnings = new ArrayList<>();
        warnings.add(new AnalysisWarning("AI-PARSE-001", "WARNING"));
        return new QueryAnalysisResponse(
                false,
                List.of(new AnalysisError(
                        "AI-PARSE-001",
                        "AI analysis could not be structured: " + (detail != null ? detail : "unknown"),
                        "Retry analysis or check Groq API configuration.",
                        null,
                        null)),
                warnings,
                List.of(),
                query,
                elapsedMs,
                metadata);
    }

    private static List<AnalysisError> parseErrors(JsonNode node) {
        List<AnalysisError> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            String code = textOr(item.path("code"), "AI-CUSTOM");
            String message = textOr(item.path("message"), "");
            String suggestion = textOr(item.path("suggestion"), "");
            Integer line = item.hasNonNull("line") ? item.path("line").asInt() : null;
            Integer column = item.hasNonNull("column") ? item.path("column").asInt() : null;
            StudentExplanationDto education = parseEducation(item.path("education"));
            list.add(new AnalysisError(code, message, suggestion, line, column, education));
        }
        return list;
    }

    private static StudentExplanationDto parseEducation(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String what = textOr(node.path("what"), null);
        String why = textOr(node.path("why"), null);
        if ((what == null || what.isBlank()) && (why == null || why.isBlank())) {
            return null;
        }
        return new StudentExplanationDto(
                what,
                why,
                textOr(node.path("example"), null),
                textOr(node.path("correctedExample"), null));
    }

    private static List<AnalysisWarning> parseWarnings(JsonNode node) {
        List<AnalysisWarning> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            String code = textOr(item.path("code"), "AI-WARN");
            String severity = textOr(item.path("severity"), "WARNING");
            list.add(new AnalysisWarning(code, severity));
        }
        return list;
    }

    private static List<OptimizationSuggestion> parseOptimizations(JsonNode node) {
        List<OptimizationSuggestion> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            list.add(new OptimizationSuggestion(
                    textOr(item.path("ruleId"), "AI-OPT"),
                    textOr(item.path("description"), ""),
                    textOr(item.path("originalFragment"), ""),
                    textOr(item.path("optimizedFragment"), ""),
                    textOr(item.path("impact"), "MEDIUM")));
        }
        return list;
    }

    private static String textOr(JsonNode node, String fallback) {
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String t = node.asText().trim();
        return t.isEmpty() ? fallback : t;
    }

    private static String stripMarkdownFences(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            if (first > 0) {
                t = t.substring(first + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        int jsonStart = t.indexOf('{');
        int jsonEnd = t.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return t.substring(jsonStart, jsonEnd + 1);
        }
        return t.trim();
    }

    /** Compact summary of a native reference analysis for the AI prompt. */
    public static String summarizeReference(QueryAnalysisResponse ref, Locale locale) {
        boolean es = com.qwerys.qwerys_backend.ai.AiLocaleHelper.isSpanish(locale);
        StringBuilder sb = new StringBuilder();
        sb.append(es ? "Válida (motor base): " : "Valid (base engine): ").append(ref.isValid()).append('\n');
        if (ref.errors() != null) {
            for (AnalysisError e : ref.errors()) {
                sb.append("- ERROR ").append(e.code()).append(": ").append(e.message()).append('\n');
            }
        }
        if (ref.warnings() != null) {
            for (AnalysisWarning w : ref.warnings()) {
                sb.append("- WARN ").append(w.code()).append('\n');
            }
        }
        if (ref.optimizations() != null) {
            for (OptimizationSuggestion o : ref.optimizations()) {
                sb.append("- OPT ").append(o.ruleId()).append(": ").append(o.description()).append('\n');
            }
        }
        return sb.toString();
    }
}
