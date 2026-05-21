package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.ai.AiClient;
import com.qwerys.qwerys_backend.ai.AiFailureReason;
import com.qwerys.qwerys_backend.ai.AiLocaleHelper;
import com.qwerys.qwerys_backend.model.ai.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class AiSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(AiSuggestionService.class);

    private final AiClient aiClient;
    private final RuleBasedAiFallback fallback;
    private final ComplementAnalysisParser complementParser;
    private final ComplementAnalysisEnricher complementEnricher;

    public AiSuggestionService(
            AiClient aiClient,
            RuleBasedAiFallback fallback,
            ComplementAnalysisParser complementParser,
            ComplementAnalysisEnricher complementEnricher) {
        this.aiClient = aiClient;
        this.fallback = fallback;
        this.complementParser = complementParser;
        this.complementEnricher = complementEnricher;
    }

    public AiResponse suggestQuery(SuggestQueryRequest req) {
        long start = System.currentTimeMillis();
        Locale locale = AiLocaleHelper.resolve(req.locale());
        String db = safeDb(req.databaseType());
        String schemaCtx = formatSchema(req.schema());
        String system = baseSystem(locale, db);
        String userPrompt = """
                        Database engine: %s
                %s
                        Schema (tables and columns):
                        %s

                        User request (natural language): %s

                Generate ONE correct statement for this engine. Output only the statement.
                """.formatted(db, AiLocaleHelper.engineSyntaxHint(db), schemaCtx, nullToEmpty(req.description()));

        return withAiOrFallback(
                () -> aiClient.complete(system, userPrompt),
                () -> fallback.suggestQuery(req),
                start);
    }

    public AiResponse autocomplete(AutocompleteRequest req) {
        long start = System.currentTimeMillis();
        Locale locale = AiLocaleHelper.resolve(req.locale());
        String db = safeDb(req.databaseType());
        String schemaCtx = formatSchema(req.schema());
        String partial = nullToEmpty(req.partialQuery());
        String system = baseSystem(locale, db);
        String userPrompt = """
                        Database engine: %s
                %s
                        Schema:
                        %s

                Partial input so far:
                %s

                Complete naturally. Return the FULL text (partial + completion), nothing else.
                """.formatted(db, AiLocaleHelper.engineSyntaxHint(db), schemaCtx, partial);

        return withAiOrFallback(
                () -> aiClient.complete(system, userPrompt),
                () -> fallback.autocomplete(req),
                start);
    }

    public AiResponse generateQuery(GenerateQueryRequest req) {
        long start = System.currentTimeMillis();
        Locale locale = AiLocaleHelper.resolve(req.locale());
        String db = safeDb(req.databaseType());
        String system = baseSystem(locale, db);
        String userPrompt = """
                        Database engine: %s
                %s
                        Operation: %s
                Table/collection/index: %s
                Columns/fields: %s
                Condition (optional): %s

                Generate ONE syntactically correct statement. Output only the statement.
                        """.formatted(
                        db,
                AiLocaleHelper.engineSyntaxHint(db),
                        nullToEmpty(req.operation()),
                        nullToEmpty(req.tableName()),
                        req.columns() != null ? String.join(", ", req.columns()) : "*",
                        req.condition() != null ? req.condition() : "");

        return withAiOrFallback(
                () -> aiClient.complete(system, userPrompt),
                () -> fallback.generateQuery(req),
                start);
    }

    public AiResponse explainErrors(ExplainErrorsRequest req) {
        long start = System.currentTimeMillis();
        Locale locale = AiLocaleHelper.resolve(req.locale());
        String db = safeDb(req.databaseType());
                String errorsText = formatErrors(req.errors());
        String system = baseSystem(locale, db);
        String userPrompt = """
                        Database engine: %s
                        Query:
                        %s

                Analysis errors:
                        %s

                Explain each issue for a learner. Give practical fix steps. Use short paragraphs, no markdown headers.
                        """.formatted(db, nullToEmpty(req.query()), errorsText);

        return withAiOrFallback(
                () -> aiClient.complete(system, userPrompt),
                () -> fallback.explainErrors(req),
                start);
    }

    public AiResponse improveMigration(ImproveMigrationRequest req) {
        long start = System.currentTimeMillis();
        String migrationSystem = """
                You are an expert code migration assistant for QWERYS.
                Output only the final migrated source code, no explanations.
                """;
        String userPrompt = """
                        Migrate code from %s to %s.

                        Original source:
                        %s

                        Current partial migration:
                        %s

                        Warnings from rule-based migrator:
                        %s

                        Manual steps still needed:
                        %s

                        Return the COMPLETE improved target code only. Fix warnings where possible.
                        """.formatted(
                        req.sourceLanguage(),
                        req.targetLanguage(),
                        nullToEmpty(req.originalCode()),
                        nullToEmpty(req.currentMigration()),
                        joinLines(req.warnings()),
                        joinLines(req.manualSteps()));

        return withAiOrFallback(
                () -> aiClient.complete(migrationSystem, userPrompt),
                () -> fallback.improveMigration(req),
                start);
    }

    public ComplementAnalysisResponse complementAnalysis(ComplementAnalysisRequest req) {
        long start = System.currentTimeMillis();
        if (!aiClient.isAvailable()) {
            log.warn("AI complement: no provider configured — using rule-based fallback");
            return fallback.complementAnalysisStructured(req, elapsed(start), AiFailureReason.NOT_CONFIGURED);
        }
        try {
            log.debug("AI complement: calling AI provider '{}' for engine {}",
                      aiClient.providerName(), req.databaseType());
            String raw = aiClient.complete(
                    buildComplementSystem(req),
                    buildComplementUser(req),
                    true);
            if (raw == null || raw.isBlank()) {
                log.warn("AI complement: empty response — fallback");
                return fallback.complementAnalysisStructured(req, elapsed(start), AiFailureReason.PARSE_ERROR);
            }
            ComplementAnalysisResponse parsed =
                    complementParser.parse(raw, true, aiClient.providerName(), elapsed(start));
            if (!parsed.success()) {
                log.warn("AI complement: JSON parse failed ({}) — fallback", parsed.error());
                return fallback.complementAnalysisStructured(req, elapsed(start), AiFailureReason.PARSE_ERROR);
            }
            ComplementAnalysisResponse sanitized = ComplementAnalysisSanitizer.sanitize(parsed, req);
            return complementEnricher.enrich(sanitized, req);
        } catch (Exception ex) {
            AiFailureReason reason = AiFailureReason.from(ex);
            log.warn("AI complement: provider error [{}] ({}) — rule-based fallback",
                     reason, ex.getMessage());
            return fallback.complementAnalysisStructured(req, elapsed(start), reason);
        }
    }

    private static String buildComplementSystem(ComplementAnalysisRequest req) {
        Locale locale = AiLocaleHelper.resolve(req.locale());
        String dialectLabel = complementDialectLabel(req);
        boolean es = AiLocaleHelper.isSpanish(locale);
        return """
                You are QWERYS, an expert database assistant (Grammarly for SQL/NoSQL).
                %s
                %s
                The native QWERYS analyzer already ran. It is the primary source of truth UNLESS you detect a clear false positive/negative due to parser limitations.
                You MUST also analyze the user's query independently and add findings the native engine may have missed.

                Return ONLY valid JSON (no markdown fences). Schema:
                {
                  "pedagogy": "2-4 sentences: concepts and how to investigate (do not repeat native messages verbatim)",
                  "optimizationNotes": "optional short notes on native OPT rules, or empty string",
                  "validityCorrection": {
                    "apply": boolean,
                    "correctedIsValid": boolean,
                    "reason": "only when apply is true — e.g. incomplete paste, wrong engine, false syntax flag"
                  },
                  "nativeReviews": [
                    { "referenceId": "SYN-001-SQL|OPT-003|...", "verdict": "AGREE|PARTIAL|DISAGREE", "comment": "..." }
                  ],
                  "additionalErrors": [
                    { "code": "AI-ERR-...", "message": "...", "suggestion": "..." }
                  ],
                  "additionalWarnings": [
                    { "code": "AI-WARN-...", "severity": "WARNING|INFO", "message": "natural language explanation (required, never empty)" }
                  ],
                  "additionalOptimizations": [
                    {
                      "ruleId": "AI-OPT-...",
                      "impact": "HIGH|MEDIUM|LOW",
                      "description": "...",
                      "originalFragment": "exact substring from user query",
                      "optimizedFragment": "improved version for this engine"
                    }
                  ],
                  "syntaxCorrections": [
                    {
                      "forErrorCode": "SYN-001-SQL",
                      "correctedQuery": "full corrected query or minimal fix",
                      "explanation": "brief"
                    }
                  ],
                  "aiSyntaxTree": {
                    "type": "ROOT_STATEMENT_TYPE (e.g. SELECT_STATEMENT, UPDATE_ITEM, FIND_QUERY, INSERT_STATEMENT)",
                    "value": null,
                    "children": [
                      { "type": "CLAUSE_TYPE (e.g. COLUMN_LIST, TABLE_REF, WHERE_CLAUSE, JOIN, GROUP_BY, ORDER_BY, OVER_CLAUSE)",
                        "value": "optional literal/identifier",
                        "children": [ /* nested nodes mirroring the query structure */ ] }
                    ]
                  }
                }

                Rules:
                - Use correct dialect for %s (MySQL: LIMIT/OFFSET, JSON_TABLE, backticks; PostgreSQL: LIMIT/OFFSET or FETCH FIRST, ILIKE, JSONB, RETURNING; Oracle: FETCH FIRST not LIMIT, DUAL, CONNECT BY, NVL; SQL Server: TOP or OFFSET/FETCH, [brackets], WITH (NOLOCK); SQLite: LIMIT/OFFSET, no RIGHT/FULL JOIN, no PIVOT; DynamoDB: if_not_exists/UpdateExpression — NEVER mix coalesce, INDEXED BY, WITH (INDEX), TOP/LIMIT/FETCH FIRST across engines).
                - additionalOptimizations must be YOUR independent suggestions (security, performance, style), not copies of native OPT cards.
                - Every additionalOptimization MUST include non-empty originalFragment and optimizedFragment using ONLY valid syntax for %s.
                - additionalWarnings.message is REQUIRED human-readable text; never return only a code like AI-WARN-001.
                - Do NOT add additionalErrors that merely repeat native errors or say "no additional errors found".
                - nativeReviews.comment must be natural language for the user; referenceId is internal metadata only.
                - syntaxCorrections: provide real corrected code when native reports syntax errors; fix typos like WHERF→WHERE if present.
                - validityCorrection.apply=true when you are confident the query is actually valid for this engine (even if native flagged false SYN on OVER/ROW_NUMBER/CTE/JSON_TABLE/LATERAL, or rejected valid NoSQL expressions). Use AGREE on native errors that are real.
                - aiSyntaxTree: REQUIRED whenever you assert validity (validityCorrection.apply=true OR you DISAGREE with a SYN error). Build a faithful tree of the user's query for this engine — clauses, joins, predicates, projections, NoSQL operators. Use UPPER_SNAKE_CASE for type. This tree is shown in expert mode when the native parser cannot build one.
                - If native isValid=%s and query is fine, still add additionalOptimizations/warnings when you see real improvements.
                - Banned anti-patterns (do NOT emit; they are folklore or technically wrong):
                  * SQL: "LIMIT without OFFSET" or "LIMIT sin OFFSET" being non-deterministic — the real issue is LIMIT without ORDER BY (the native engine already reports that as SE010).
                  * SQL: Suggesting an index on a boolean column/field (e.g. active, enabled, is_deleted) — low cardinality, b-tree gives almost no benefit.
                  * SQL: Returning EXPLAIN <query> as an "optimization" — EXPLAIN is diagnostic, not an optimization. Same rule applies to SET SHOWPLAN/STATISTICS ON.
                  * NoSQL diagnostics (NEVER emit as optimization):
                      - MongoDB: db.coll.find(...).explain("executionStats"), db.coll.stats(), getIndexStats().
                      - Cassandra: TRACING ON; ... TRACING OFF; — diagnostic only.
                      - Elasticsearch: GET /idx/_search?explain=true, "explain": true, GET /_nodes/stats, GET /_cluster/health — diagnostic only.
                      - DynamoDB: aws dynamodb describe-table / describe-contributor-insights — diagnostic only.
                      - Redis: DEBUG OBJECT, INFO, MONITOR — diagnostic only.
                  * NoSQL folklore (NEVER emit as recommendation):
                      - Cassandra: "use ALLOW FILTERING" as a general recommendation — it is the textbook anti-pattern, full partition scan.
                      - Redis: "use KEYS *" or "use KEYS for backup/scan" — blocks the server; SCAN/HSCAN is the right primitive.
                      - Redis: "use FLUSHALL to clean up" — destroys production data.
                      - MongoDB: "$where for complex filters" — disables indexes, runs full JS interpreter per doc.
                      - MongoDB: "always shard your collection" without dataset/throughput context.
                      - MongoDB/Cassandra: "denormalize everything" as a blanket rule — denormalization is workload-driven, not universal.
                      - Elasticsearch: "disable refresh_interval permanently" — loses near-real-time semantics.
                      - DynamoDB: "store JSON as a single String attribute" — defeats partial updates and indexing.
                  * Duplicating any native finding sent in the user prompt — review native (AGREE/PARTIAL/DISAGREE) instead of re-emitting. Examples of native ruleIds you must NOT re-emit as additionalOptimizations/Errors/Warnings:
                      - Generic SQL OPT: OPT-001 SELECT *, OPT-002 missing index, OPT-003 LIMIT, OPT-005 subquery→JOIN.
                      - MySQL: MY001-MY006. PostgreSQL: PG001-PG006. Oracle: ORA001-ORA009. SQL Server: SS001-SS008. SQLite: LT001-LT006. Generic semantic: SE001-SE020.
                      - MongoDB: MGO-NOFILTER-001, MGO-CHAIN-001, MGO-CS-001/002/003/004, MGO-TX-001..004, MGO-SYNTAX-001, MGO-WRONG-ENGINE.
                      - Cassandra: CQL-FULLSCAN-001, CQL-BATCH-APPLY/LARGE/003/005, CAS-BATCH-001..005, CAS-LWT-001/002/003, CQL-SYN-001.
                      - Redis: RDS-CMD-001 (KEYS *), RDS-KEYS-001/002, RDS-FLUSH-001, RDS-SESS-001, RDS-PIP-001/002/003, RDS-TX-001..004, RDS-PUB-001, RDS-HMSET-001, RDS-INJ-001.
                      - Elasticsearch: ES-DEEP-PAGE, ES-SIZE-LIMIT, ES-MATCH-ALL, ES-NESTED-DEPTH, ES-SCRIPT, ES-REGEX, ES-TRACK-TOTAL, ES-EXPLAIN, ES-PROFILE, ES-FUZZY-HIGH.
                      - DynamoDB: DDB-EXPR-*, DDB-KCE-*, DDB-FE-*, DDB-PE-*, DDB-UE-*.
                  * Sentences that copy native messages verbatim or rephrase them trivially.
                - additionalOptimizations.originalFragment MUST be an exact substring of the user query when possible; if not, return a precise rewrite using the real table/column identifiers from the query, never placeholders like "your_table", "table_name", or "?".
                - Output MUST be strictly parseable JSON: escape every double-quote and backslash inside string values; never emit string-concatenation operators (+) outside JSON string quotes (e.g. use \\" inside the JSON string for quotes in SQL fragments).
                - %s
                - %s
                %s
                """.formatted(
                AiLocaleHelper.languageInstruction(locale),
                complementEngineHint(req),
                dialectLabel,
                dialectLabel,
                req.nativeIsValid() != null ? req.nativeIsValid() : "?",
                customEngineSystemAddendum(req),
                scriptScopeSystemAddendum(req),
                es
                        ? "Responde textos en español."
                        : "Write text fields in English.");
    }

    /** Append-only custom-engine block; empty for native engines. */
    private static String customEngineSystemAddendum(ComplementAnalysisRequest req) {
        return CustomEngineAnalysisSupport.resolveContext(req)
                .map(ctx -> CustomEngineAnalysisSupport.promptAddendum(
                        ctx, AiLocaleHelper.resolve(req.locale())))
                .orElse("");
    }

    private static String complementEngineHint(ComplementAnalysisRequest req) {
        return CustomEngineAnalysisSupport.resolveContext(req)
                .map(CustomEngineAnalysisSupport::customEngineSyntaxHint)
                .orElseGet(() -> AiLocaleHelper.engineSyntaxHint(safeDb(req.databaseType())));
    }

    private static String complementDialectLabel(ComplementAnalysisRequest req) {
        return CustomEngineAnalysisSupport.resolveContext(req)
                .map(CustomEngineContext::customName)
                .orElse(safeDb(req.databaseType()));
    }

    /**
     * Extra system instructions when the client requests a whole-script (cross-statement) complement pass.
     * Appended to the polished prompt — does not replace native-first / validityCorrection rules.
     */
    private static String scriptScopeSystemAddendum(ComplementAnalysisRequest req) {
        if (!isScriptScope(req)) {
            return "";
        }
        int n = req.statementCount() != null && req.statementCount() > 0 ? req.statementCount() : 1;
        return """

                SCRIPT-LEVEL PASS (analysisScope=SCRIPT):
                - You are reviewing the ENTIRE user input (%d statement(s) in this script), not a single numbered card in isolation.
                - Focus on cross-statement concerns: transaction boundaries (BEGIN/COMMIT/ROLLBACK), session/connection settings, statement ordering, idempotency, mixed DDL+DML risk, batch semantics, and consistency across the script.
                - Native findings below come from the whole-script summary; per-statement analysis ran separately—do NOT repeat each statement's native errors verbatim.
                - Use additionalWarnings/additionalErrors for script-wide issues the per-statement pass cannot see.
                - validityCorrection at script level only when the whole script is valid for this engine despite native script-level false positives (parser limitations, wrong engine flag on valid procedural SQL, etc.).
                - When native marked INVALID due to parser gaps but the script is valid for this engine, apply validityCorrection and DISAGREE on relevant SYN codes — same lifesaver role as single-statement complement.
                - aiSyntaxTree is optional at script level; prefer clear pedagogy and cross-statement warnings.
                """.formatted(n);
    }

    private static boolean isScriptScope(ComplementAnalysisRequest req) {
        return req != null
                && req.analysisScope() != null
                && "SCRIPT".equalsIgnoreCase(req.analysisScope().trim());
    }

    private static String buildComplementUser(ComplementAnalysisRequest req) {
        String dialectLabel = complementDialectLabel(req);
        String scopeHeader = buildScopeHeader(req);
        String customHeader = CustomEngineAnalysisSupport.resolveContext(req)
                .map(ctx -> CustomEngineAnalysisSupport.complementUserHeader(
                        ctx, AiLocaleHelper.resolve(req.locale())))
                .map(h -> h + "\n\n")
                .orElse("");
        String queryBody = isScriptScope(req)
                ? nullToEmpty(req.fullScript() != null && !req.fullScript().isBlank()
                        ? req.fullScript()
                        : req.query())
                : nullToEmpty(req.query());
        return customHeader
                + """
                %s
                Database engine: %s
                Native isValid: %s
                Query:
                %s

                Live schema note:
                %s

                Native errors:
                %s

                Native warnings:
                %s

                Native optimizations (ruleId | impact | description):
                %s

                Analyze the query yourself. Return JSON only.
                """.formatted(
                scopeHeader,
                dialectLabel,
                req.nativeIsValid() != null ? req.nativeIsValid() : "unknown",
                queryBody,
                req.liveSchemaNote() != null ? req.liveSchemaNote() : "(none)",
                formatErrors(req.errors()),
                formatWarnings(req.warnings()),
                formatOptimizationsDetailed(req.optimizations()));
    }

    private static String buildScopeHeader(ComplementAnalysisRequest req) {
        if (isScriptScope(req)) {
            int n = req.statementCount() != null ? req.statementCount() : 0;
            return "Analysis scope: SCRIPT (entire input, " + n + " statement(s)).";
        }
        if (req.statementIndex() != null && req.statementCount() != null && req.statementCount() > 0) {
            return "Analysis scope: STATEMENT " + req.statementIndex() + " of " + req.statementCount() + ".";
        }
        return "Analysis scope: STATEMENT.";
    }

    private static String formatOptimizationsDetailed(List<OptimizationDto> optimizations) {
        if (optimizations == null || optimizations.isEmpty()) {
            return "(none)";
        }
        return optimizations.stream()
                .map(o -> (o.ruleId() != null ? o.ruleId() : "?")
                        + " | "
                        + (o.impact() != null ? o.impact() : "")
                        + " | "
                        + nullToEmpty(o.description())
                        + "\n  original: "
                        + nullToEmpty(o.originalFragment())
                        + "\n  optimized: "
                        + nullToEmpty(o.optimizedFragment()))
                .collect(Collectors.joining("\n"));
    }

    public AiResponse explainSecurityFinding(ExplainSecurityRequest req) {
        long start = System.currentTimeMillis();
        Locale locale = AiLocaleHelper.resolve(req.locale());
        String db = safeDb(req.databaseType());
        String system = baseSystem(locale, db);
        String userPrompt = """
                        Database engine: %s
                        Security rule already triggered: %s (%s)
                        Risk summary: %s

                        Query from user history:
                        %s

                Explain why this pattern is dangerous and give 2-3 concrete remediation steps.
                        Do not repeat the query verbatim at length. Plain text, no markdown headers.
                        """.formatted(
                        db,
                nullToEmpty(req.ruleKey()),
                        nullToEmpty(req.patternId()),
                nullToEmpty(req.riskSummary()),
                        nullToEmpty(req.query()));

        return withAiOrFallback(
                () -> aiClient.complete(system, userPrompt),
                () -> fallback.explainSecurityFinding(req),
                start);
    }

    public boolean isAvailable() {
        return aiClient.isAvailable();
    }

    private static String baseSystem(Locale locale, String databaseType) {
        return """
                You are QWERYS, an expert database assistant (Grammarly for SQL/NoSQL).
                %s
                %s
                Respond ONLY with the requested output—no markdown fences unless asked.
                """.formatted(
                AiLocaleHelper.languageInstruction(locale),
                AiLocaleHelper.engineSyntaxHint(databaseType));
    }

    private static String formatSchema(List<TableInfo> schema) {
        if (schema == null || schema.isEmpty()) {
            return "(no schema provided)";
        }
        return schema.stream()
                .map(t -> t.name() + ": " + (t.columns() != null ? String.join(", ", t.columns()) : ""))
                .collect(Collectors.joining("\n"));
    }

    private static String formatErrors(List<AnalysisErrorDto> errors) {
        if (errors == null) {
            return "";
        }
        return errors.stream()
                .map(e -> e.code() + " | " + e.message() + " | " + e.suggestion())
                .collect(Collectors.joining("\n"));
    }

    private static String formatWarnings(List<AnalysisWarningDto> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return "(none)";
        }
        return warnings.stream()
                .map(w -> w.code() + " [" + w.severity() + "]"
                        + (w.message() != null && !w.message().isBlank() ? " — " + w.message() : ""))
                .collect(Collectors.joining("\n"));
    }

    private static String formatOptimizations(List<OptimizationDto> optimizations) {
        if (optimizations == null || optimizations.isEmpty()) {
            return "(none)";
        }
        return optimizations.stream()
                .map(o -> (o.ruleId() != null ? o.ruleId() : "?")
                        + " "
                        + (o.impact() != null ? o.impact() : "")
                        + " | "
                        + nullToEmpty(o.description()))
                .collect(Collectors.joining("\n"));
    }

    private static String joinLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return "(none)";
        }
        return String.join("\n", lines);
    }

    private static String safeDb(String db) {
        return db == null || db.isBlank() ? "mysql" : db.toLowerCase(Locale.ROOT);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private AiResponse withAiOrFallback(
            java.util.function.Supplier<String> aiCall,
            java.util.function.Supplier<String> fallbackCall,
            long start) {
        if (!aiClient.isAvailable()) {
            return AiResponse.ok(fallbackCall.get(), false, "rule-based", elapsed(start));
        }
        try {
            String result = aiCall.get();
            if (result == null || result.isBlank()) {
                return AiResponse.ok(fallbackCall.get(), false, "rule-based-fallback", elapsed(start));
            }
            return AiResponse.ok(result, true, aiClient.providerName(), elapsed(start));
        } catch (Exception ex) {
            return AiResponse.ok(fallbackCall.get(), false, "rule-based-fallback", elapsed(start));
        }
    }
}
