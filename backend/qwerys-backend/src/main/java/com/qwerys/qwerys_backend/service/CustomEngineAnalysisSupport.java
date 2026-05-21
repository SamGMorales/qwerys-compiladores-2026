package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.ai.AiLocaleHelper;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisRequest;
import com.qwerys.qwerys_backend.model.ai.NativeFindingReviewDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Custom-engine-only helpers: prompt addenda, SQL-like query detection, and sanitizer guards.
 * Does not affect the ten native analyzers.
 */
final class CustomEngineAnalysisSupport {

    private static final Pattern SQL_LIKE_SELECT_FROM = Pattern.compile(
            "(?is)\\bSELECT\\b.+\\bFROM\\b");

    /** Couchbase/N1QL-style bucket.scope.collection paths. */
    private static final Pattern BACKTICK_QUALIFIED_PATH = Pattern.compile(
            "`[^`]+`\\s*(?:\\.\\s*`[^`]+`\\s*){1,2}");

    private static final Pattern MONGO_SHELL = Pattern.compile(
            "(?is)\\bdb\\.[a-zA-Z0-9_]+\\.(find|aggregate|update|delete|insert)\\s*\\(");

    private static final Pattern REDIS_COMMAND = Pattern.compile(
            "(?is)^\\s*(GET|SET|HGET|HSET|DEL|EVAL|KEYS|SCAN)\\b");

    private CustomEngineAnalysisSupport() {
    }

    static boolean isCustomEngine(String databaseType) {
        return CustomEngineContext.isCustomDeclaration(databaseType);
    }

    static Optional<CustomEngineContext> resolveContext(String databaseType, String customEngineBase) {
        if (!isCustomEngine(databaseType)) {
            return Optional.empty();
        }
        CustomEngineContext ctx = CustomEngineContext.fromDeclaration(databaseType, customEngineBase);
        return Optional.of(ctx);
    }

    static Optional<CustomEngineContext> resolveContext(ComplementAnalysisRequest req) {
        if (req == null) {
            return Optional.empty();
        }
        return resolveContext(req.databaseType(), req.customEngineBase());
    }

    /**
     * SQL-like document query languages (N1QL, SQL++, CQL-style SELECT, etc.).
     */
    static boolean isSqlLikeDocumentQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        if (SQL_LIKE_SELECT_FROM.matcher(query).find()) {
            return true;
        }
        return BACKTICK_QUALIFIED_PATH.matcher(query).find();
    }

    static boolean isShellOrKvReferenceBase(String referenceBase) {
        if (referenceBase == null || referenceBase.isBlank()) {
            return false;
        }
        return switch (referenceBase.strip().toLowerCase(Locale.ROOT)) {
            case "mongodb", "redis" -> true;
            default -> false;
        };
    }

    static boolean shouldGuardWrongEngineAgree(String query, String customEngineBase) {
        return isSqlLikeDocumentQuery(query)
                && isShellOrKvReferenceBase(customEngineBase);
    }

    static boolean referenceIdIsWrongEngine(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            return false;
        }
        return referenceId.toUpperCase(Locale.ROOT).contains("WRONG-ENGINE");
    }

    static String customEngineSyntaxHint(CustomEngineContext ctx) {
        String baseHint = AiLocaleHelper.engineSyntaxHint(ctx.referenceBase());
        return baseHint
                + " Custom engine \""
                + ctx.customName()
                + "\": that hint describes partial similarity to "
                + ctx.referenceBase()
                + " only. If the user query is SQL-like (SELECT/FROM/WHERE or backtick bucket paths),"
                + " treat it as valid surface syntax for "
                + ctx.customName()
                + " (e.g. N1QL), not as "
                + ctx.referenceBase()
                + " shell/API syntax.";
    }

    /** Append-only block for {@link CustomEngineAiAnalyzer} and complement system prompts. */
    static String promptAddendum(CustomEngineContext ctx, Locale locale) {
        boolean es = AiLocaleHelper.isSpanish(locale);
        if (es) {
            return """

                    MOTOR PERSONALIZADO (solo contexto %s):
                    - El veredicto es para el motor declarado "%s", NO para "%s".
                    - "%s" es solo similitud parcial / brujula estructural; no copies su dialecto cuando %s use otro lenguaje de consulta.
                    - Si la query es SQL-like (SELECT/FROM/WHERE, rutas `bucket`.`scope`.`collection`), analizala como dialecto documento/SQL de %s (p. ej. N1QL), no como shell de %s.
                    - No emitas WRONG-ENGINE ni marques sintaxis invalida solo porque la query parezca SQL mientras la referencia sea %s.
                    """.formatted(
                    ctx.customName(),
                    ctx.customName(),
                    ctx.referenceBase(),
                    ctx.referenceBase(),
                    ctx.customName(),
                    ctx.customName(),
                    ctx.referenceBase(),
                    ctx.referenceBase());
        }
        return """

                CUSTOM ENGINE (context %s only):
                - Verdict is for declared engine "%s", NOT for "%s".
                - "%s" is partial similarity / structural hint only; do not force its dialect when %s uses a different query language.
                - If the query is SQL-like (SELECT/FROM/WHERE or `bucket`.`scope`.`collection` paths), analyze it as %s document/SQL dialect (e.g. N1QL), not as %s shell/API syntax.
                - Do NOT emit WRONG-ENGINE or invalid-syntax solely because the query looks SQL-like while the reference base is %s.
                """.formatted(
                ctx.customName(),
                ctx.customName(),
                ctx.referenceBase(),
                ctx.referenceBase(),
                ctx.customName(),
                ctx.customName(),
                ctx.referenceBase(),
                ctx.referenceBase());
    }

    static String complementUserHeader(CustomEngineContext ctx, Locale locale) {
        boolean es = AiLocaleHelper.isSpanish(locale);
        if (es) {
            return "Motor personalizado: "
                    + ctx.customName()
                    + " | Referencia (solo guia): "
                    + ctx.referenceBase()
                    + " | El analisis nativo previo proviene de IA primaria para el motor custom, no del parser nativo de "
                    + ctx.referenceBase()
                    + ".";
        }
        return "Custom engine: "
                + ctx.customName()
                + " | Reference (hint only): "
                + ctx.referenceBase()
                + " | Prior native output is primary AI analysis for the custom engine, not "
                + ctx.referenceBase()
                + "'s native parser.";
    }

    static List<NativeFindingReviewDto> adjustWrongEngineReviewsForCustomSqlLike(
            List<NativeFindingReviewDto> reviews,
            ComplementAnalysisRequest req,
            Locale locale) {
        if (reviews == null || reviews.isEmpty() || req == null) {
            return reviews != null ? reviews : List.of();
        }
        Optional<CustomEngineContext> ctxOpt = resolveContext(req);
        if (ctxOpt.isEmpty()) {
            return reviews;
        }
        if (!shouldGuardWrongEngineAgree(req.query(), req.customEngineBase())) {
            return reviews;
        }
        List<NativeFindingReviewDto> out = new ArrayList<>(reviews.size());
        for (NativeFindingReviewDto r : reviews) {
            if (r == null) {
                continue;
            }
            String verdict = r.verdict() != null ? r.verdict().toUpperCase(Locale.ROOT) : "";
            if (referenceIdIsWrongEngine(r.referenceId())
                    && ("AGREE".equals(verdict) || "PARTIAL".equals(verdict))) {
                out.add(new NativeFindingReviewDto(
                        r.referenceId(),
                        "DISAGREE",
                        disagreeWrongEngineComment(ctxOpt.get(), locale)));
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private static String disagreeWrongEngineComment(CustomEngineContext ctx, Locale locale) {
        if (AiLocaleHelper.isSpanish(locale)) {
            return "Falso positivo: la consulta SQL-like es sintaxis valida para "
                    + ctx.customName()
                    + " (p. ej. N1QL); la referencia "
                    + ctx.referenceBase()
                    + " no debe usarse como veredicto de dialecto.";
        }
        return "False positive: the SQL-like query is valid surface syntax for "
                + ctx.customName()
                + " (e.g. N1QL); reference base "
                + ctx.referenceBase()
                + " must not override the custom engine dialect.";
    }
}
