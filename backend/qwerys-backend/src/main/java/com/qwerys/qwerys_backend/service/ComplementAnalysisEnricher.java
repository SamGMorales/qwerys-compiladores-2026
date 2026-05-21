package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.ai.AiLocaleHelper;
import com.qwerys.qwerys_backend.adapter.DatabaseConfig;
import com.qwerys.qwerys_backend.dto.AstNodeDto;
import com.qwerys.qwerys_backend.dto.AnalysisMetricsDto;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import com.qwerys.qwerys_backend.model.ai.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Apply AI second-pass validity correction. When AI confidently asserts the query is valid
 * (validityCorrection.apply=true or DISAGREE on a SYN-* finding), trust it: clear native
 * errors, set the overlay AST from native re-parse if it succeeds, else from the AI-supplied
 * syntax tree so expert mode still has a tree to render.
 */
@Component
public class ComplementAnalysisEnricher {

    private static final Logger log = LoggerFactory.getLogger(ComplementAnalysisEnricher.class);

    private final QueryAnalysisService queryAnalysisService;

    public ComplementAnalysisEnricher(QueryAnalysisService queryAnalysisService) {
        this.queryAnalysisService = queryAnalysisService;
    }

    ComplementAnalysisResponse enrich(ComplementAnalysisResponse complement, ComplementAnalysisRequest req) {
        if (complement == null || !complement.success()) {
            return complement;
        }

        boolean aiClaimsValid = aiClaimsNativeSyntaxWrong(complement);
        if (!aiClaimsValid) {
            return stripFalseValidity(complement);
        }

        AstNodeDto aiTreeFromPrompt = complement.secondPassOverlay() != null
                ? complement.secondPassOverlay().astTree()
                : null;

        AstNodeDto nativeAst = null;
        AnalysisMetricsDto nativeMetrics = null;
        boolean reparseSucceeded = false;
        try {
            QueryRequest qreq = toQueryRequest(req);
            QueryAnalysisResponse reparse = queryAnalysisService.analyzeNativeOnly(qreq, true);
            if (reparse != null && reparse.isValid() && reparse.astTree() != null) {
                nativeAst = reparse.astTree();
                nativeMetrics = reparse.metrics();
                reparseSucceeded = true;
                log.info("AI second pass: native re-parse confirmed validity for engine {}", req.databaseType());
            } else {
                log.info("AI second pass: native re-parse could not confirm; trusting AI tree for engine {}",
                        req.databaseType());
            }
        } catch (Exception ex) {
            log.info("AI second pass: native re-parse threw ({}) — trusting AI tree", ex.getMessage());
        }

        AstNodeDto effectiveTree = nativeAst != null ? nativeAst : aiTreeFromPrompt;

        ValidityCorrectionDto vc = new ValidityCorrectionDto(
                true,
                true,
                resolveReason(complement, req, reparseSucceeded));

        AiSecondPassOverlayDto overlay = new AiSecondPassOverlayDto(
                true,
                reparseSucceeded,
                true,
                effectiveTree,
                nativeMetrics);

        return copyWith(complement, vc, overlay, complement.pedagogy());
    }

    private static boolean aiClaimsNativeSyntaxWrong(ComplementAnalysisResponse complement) {
        if (complement.validityCorrection() != null
                && complement.validityCorrection().apply()
                && complement.validityCorrection().correctedIsValid()) {
            return true;
        }
        if (complement.nativeReviews() == null) {
            return false;
        }
        return complement.nativeReviews().stream().anyMatch(ComplementAnalysisEnricher::isSynDisagree);
    }

    private static boolean isSynDisagree(NativeFindingReviewDto r) {
        if (r == null || r.referenceId() == null) {
            return false;
        }
        String id = r.referenceId().toUpperCase(Locale.ROOT);
        String verdict = r.verdict() != null ? r.verdict().toUpperCase(Locale.ROOT) : "";
        return id.startsWith("SYN") && ("DISAGREE".equals(verdict) || "PARTIAL".equals(verdict));
    }

    private static ComplementAnalysisResponse stripFalseValidity(ComplementAnalysisResponse complement) {
        if (complement.validityCorrection() == null && complement.secondPassOverlay() == null) {
            return complement;
        }
        return copyWith(complement, null, null, complement.pedagogy());
    }

    private static ComplementAnalysisResponse copyWith(
            ComplementAnalysisResponse c,
            ValidityCorrectionDto vc,
            AiSecondPassOverlayDto overlay,
            String pedagogy) {
        return new ComplementAnalysisResponse(
                c.success(),
                pedagogy,
                c.optimizationNotes(),
                vc,
                c.nativeReviews(),
                c.additionalErrors(),
                c.additionalWarnings(),
                c.additionalOptimizations(),
                c.syntaxCorrections(),
                overlay,
                c.aiAvailable(),
                c.provider(),
                c.responseTimeMs(),
                c.error());
    }

    private static String resolveReason(
            ComplementAnalysisResponse complement,
            ComplementAnalysisRequest req,
            boolean reparseSucceeded) {
        if (complement.validityCorrection() != null
                && complement.validityCorrection().reason() != null
                && !complement.validityCorrection().reason().isBlank()) {
            return complement.validityCorrection().reason();
        }
        boolean es = AiLocaleHelper.isSpanish(AiLocaleHelper.resolve(req.locale()));
        if (reparseSucceeded) {
            return es
                    ? "La segunda pasada IA y el re-parse nativo confirman que la consulta es válida para este motor."
                    : "AI second pass and native re-parse confirm the query is valid for this engine.";
        }
        return es
                ? "La IA confirma que la consulta es válida para este motor. El analizador nativo no pudo construir el árbol; se muestra el AST apoyado por IA."
                : "AI confirms the query is valid for this engine. Native parser could not build a tree; AI-supported AST is shown.";
    }

    private static QueryRequest toQueryRequest(ComplementAnalysisRequest req) {
        DatabaseConfig conn = req.connection();
        return new QueryRequest(
                req.query() != null ? req.query() : "",
                req.databaseType() != null ? req.databaseType() : "mysql",
                req.queryType(),
                req.dialect(),
                req.locale(),
                conn,
                req.customEngineBase());
    }
}
