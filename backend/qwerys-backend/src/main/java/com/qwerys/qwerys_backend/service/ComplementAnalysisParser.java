package com.qwerys.qwerys_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerys.qwerys_backend.dto.AstNodeDto;
import com.qwerys.qwerys_backend.model.ai.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ComplementAnalysisParser {

    private final ObjectMapper objectMapper;

    public ComplementAnalysisParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ComplementAnalysisResponse parse(String rawJson, boolean aiAvailable, String provider, long ms) {
        String cleaned = stripMarkdownFences(rawJson);
        Exception lastError = null;
        for (String candidate : jsonParseCandidates(cleaned)) {
            try {
                JsonNode root = objectMapper.readTree(candidate);
                return buildOkResponse(root, aiAvailable, provider, ms);
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        String message = lastError != null ? lastError.getMessage() : "unknown parse error";
        return ComplementAnalysisResponse.fail(
                "Could not parse AI complement JSON: " + message,
                aiAvailable,
                provider,
                ms);
    }

    private static String[] jsonParseCandidates(String cleaned) {
        String repaired = ComplementJsonRepair.repair(cleaned);
        if (repaired.equals(cleaned)) {
            return new String[] { cleaned };
        }
        return new String[] { cleaned, repaired };
    }

    private ComplementAnalysisResponse buildOkResponse(
            JsonNode root, boolean aiAvailable, String provider, long ms) {
        String pedagogy = textOr(root.path("pedagogy"), "");
        String optimizationNotes = textOr(root.path("optimizationNotes"), null);

        ValidityCorrectionDto validity = parseValidity(root.path("validityCorrection"));
        List<NativeFindingReviewDto> reviews = parseReviews(root.path("nativeReviews"));
        List<AnalysisErrorDto> errors = parseErrors(root.path("additionalErrors"));
        List<AnalysisWarningDto> warnings = parseWarnings(root.path("additionalWarnings"));
        List<OptimizationDto> opts = parseOptimizations(root.path("additionalOptimizations"));
        List<SyntaxCorrectionDto> syntax = parseSyntaxCorrections(root.path("syntaxCorrections"));
        AstNodeDto aiTree = parseAiSyntaxTree(root.path("aiSyntaxTree"));
        AiSecondPassOverlayDto initialOverlay = aiTree != null
                ? new AiSecondPassOverlayDto(false, false, true, aiTree, null)
                : null;

        return ComplementAnalysisResponse.ok(
                pedagogy,
                optimizationNotes,
                validity,
                reviews,
                errors,
                warnings,
                opts,
                syntax,
                initialOverlay,
                aiAvailable,
                provider,
                ms);
    }

    private static ValidityCorrectionDto parseValidity(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.path("apply").asBoolean(false)) {
            return null;
        }
        return new ValidityCorrectionDto(
                true,
                node.path("correctedIsValid").asBoolean(false),
                textOr(node.path("reason"), ""));
    }

    private static List<NativeFindingReviewDto> parseReviews(JsonNode node) {
        List<NativeFindingReviewDto> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            list.add(new NativeFindingReviewDto(
                    textOr(item.path("referenceId"), "?"),
                    textOr(item.path("verdict"), "PARTIAL"),
                    textOr(item.path("comment"), "")));
        }
        return list;
    }

    private static List<AnalysisErrorDto> parseErrors(JsonNode node) {
        List<AnalysisErrorDto> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            list.add(new AnalysisErrorDto(
                    textOr(item.path("code"), "AI-ERR"),
                    textOr(item.path("message"), ""),
                    textOr(item.path("suggestion"), "")));
        }
        return list;
    }

    private static List<AnalysisWarningDto> parseWarnings(JsonNode node) {
        List<AnalysisWarningDto> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            String msg = textOr(item.path("message"), null);
            if (msg == null) {
                msg = textOr(item.path("description"), "");
            }
            list.add(new AnalysisWarningDto(
                    textOr(item.path("code"), "AI-WARN"),
                    textOr(item.path("severity"), "WARNING"),
                    msg.isBlank() ? null : msg));
        }
        return list;
    }

    private static List<OptimizationDto> parseOptimizations(JsonNode node) {
        List<OptimizationDto> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            String original = textOr(item.path("originalFragment"), null);
            if (original == null) {
                original = textOr(item.path("original"), "");
            }
            String optimized = textOr(item.path("optimizedFragment"), null);
            if (optimized == null) {
                optimized = textOr(item.path("optimized"), "");
            }
            list.add(new OptimizationDto(
                    textOr(item.path("ruleId"), "AI-OPT"),
                    textOr(item.path("impact"), "MEDIUM"),
                    textOr(item.path("description"), ""),
                    original,
                    optimized));
        }
        return list;
    }

    private static List<SyntaxCorrectionDto> parseSyntaxCorrections(JsonNode node) {
        List<SyntaxCorrectionDto> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            String corrected = textOr(item.path("correctedQuery"), null);
            if (corrected == null || corrected.isBlank()) {
                continue;
            }
            list.add(new SyntaxCorrectionDto(
                    textOr(item.path("forErrorCode"), "SYN-001-SQL"),
                    corrected,
                    textOr(item.path("explanation"), null)));
        }
        return list;
    }

    private static AstNodeDto parseAiSyntaxTree(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return null;
        }
        String type = textOr(node.path("type"), null);
        if (type == null) {
            return null;
        }
        String value = textOr(node.path("value"), null);
        List<AstNodeDto> children = new ArrayList<>();
        JsonNode childArr = node.path("children");
        if (childArr.isArray()) {
            for (JsonNode c : childArr) {
                AstNodeDto child = parseAiSyntaxTree(c);
                if (child != null) {
                    children.add(child);
                }
            }
        }
        return new AstNodeDto(type, value, children);
    }

    private static String textOr(JsonNode node, String fallback) {
        if (node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String t = node.asText().trim();
        return t.isEmpty() ? fallback : t;
    }

    static String stripMarkdownFences(String text) {
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
}
