package com.qwerys.qwerys_backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerys.qwerys_backend.adapter.DatabaseConfig;
import com.qwerys.qwerys_backend.analyzer.*;
import com.qwerys.qwerys_backend.analyzer.schema.LiveSchemaEnrichment;
import com.qwerys.qwerys_backend.analyzer.procedural.ProceduralSemanticAnalyzer;

import com.qwerys.qwerys_backend.analyzer.procedural.MySqlPsmAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.PlPgSqlAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.PlSqlAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.TSqlAnalyzer;
import com.qwerys.qwerys_backend.dto.AnalysisMetricsDto;
import com.qwerys.qwerys_backend.dto.AstNodeDto;
import com.qwerys.qwerys_backend.model.AnalysisError;
import com.qwerys.qwerys_backend.model.AnalysisErrors;
import com.qwerys.qwerys_backend.model.AnalysisWarning;
import com.qwerys.qwerys_backend.model.MultiStatementAnalysisResponse;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;
import com.qwerys.qwerys_backend.model.QueryAnalysisResponse;
import com.qwerys.qwerys_backend.model.QueryRequest;
import com.qwerys.qwerys_backend.history.QueryHistoryService;
import com.qwerys.qwerys_backend.optimization.OptimizationEngine;
import com.qwerys.qwerys_backend.optimization.OptimizationResult;
import com.qwerys.qwerys_backend.optimization.SchemaOptimizationSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Orchestrates the full query analysis pipeline for QWERYS.
 *
 * <p>For SQL queries: lexing → parsing → semantic analysis → optimization.
 * <p>For NoSQL queries: delegated to dedicated analyzers (Days 15–24).
 */
@Service
public class QueryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(QueryAnalysisService.class);

    private static final Set<String> SQL_DATABASES = Set.of(
            "mysql", "postgresql", "sqlite", "sqlserver", "oracle"
    );

    /**
     * PartiQL {@code UPDATE <table> SET ...} (Day 21). Must not be routed to the SDK expression
     * analyzer (Day 22).
     */
    private static final Pattern DDB_PARTIQL_UPDATE = Pattern.compile(
            "(?is)^UPDATE\\s+(\"[^\"]*\"|'[^']*'|[A-Za-z_][A-Za-z0-9_.]*)\\s+SET\\b");

    /** SDK {@code UpdateExpression} leading clause. */
    private static final Pattern DDB_SDK_UPDATE_LEADING = Pattern.compile(
            "(?is)^(SET|REMOVE|ADD|DELETE)\\b");

    private static final Pattern DDB_SDK_CONDITION_OPS = Pattern.compile("[=<>!]");

    private static final ObjectMapper DDB_JSON_ROUTER = new ObjectMapper();

    private final OptimizationEngine optimizationEngine;
    private final QueryHistoryService historyService;

    private static Locale resolveUiLocale(QueryRequest request) {
        String tag = request.locale();
        if (tag == null || tag.isBlank()) {
            return Locale.ENGLISH;
        }
        return Locale.forLanguageTag(tag.trim().replace('_', '-'));
    }

    private static boolean isSpanishUi(Locale ui) {
        return ui != null && ui.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
    }

    static List<AnalysisWarning> warningsFrom(List<SemanticError> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        return findings.stream()
                .filter(e -> e.severity() != SemanticError.Severity.ERROR)
                .map(e -> new AnalysisWarning(e.code(), e.severity().name()))
                .toList();
    }

    static List<AnalysisWarning> warningsFromDdb(List<DynamoDbSemanticError> findings) {
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        return findings.stream()
                .filter(e -> e.severity() != SemanticError.Severity.ERROR)
                .map(e -> new AnalysisWarning(e.code(), e.severity().name()))
                .toList();
    }

    private static List<AnalysisWarning> mergeWarnings(List<AnalysisWarning> first, List<AnalysisWarning> second) {
        if (first == null || first.isEmpty()) {
            return second == null ? List.of() : second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        List<AnalysisWarning> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        return merged;
    }

    private static java.util.Optional<QueryAnalysisResponse> guardWrongEngine(
            String query,
            CrossEngineSyntaxGuard.TargetEngine engine,
            long startNano,
            Locale ui) {
        return CrossEngineSyntaxGuard.check(query, engine, ui).map(err -> wrongEngineResponse(err, query, startNano));
    }

    private static QueryAnalysisResponse wrongEngineResponse(SemanticError err, String query, long startNano) {
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(
                false,
                List.of(toApiError(err)),
                List.of(),
                List.of(),
                query,
                elapsedMs);
    }

    private static String synSqlSuggestion(Locale ui) {
        return isSpanishUi(ui)
                ? "Revisa la sintaxis en la posición indicada."
                : "Check the syntax at the indicated position.";
    }

    private static String synPartiqlSuggestion(Locale ui) {
        return isSpanishUi(ui)
                ? "Revisa la sintaxis PartiQL en la posición indicada."
                : "Check PartiQL syntax at the indicated position.";
    }

    private final CustomEngineAiAnalyzer customEngineAiAnalyzer;

    public QueryAnalysisService(
            OptimizationEngine optimizationEngine,
            QueryHistoryService historyService,
            CustomEngineAiAnalyzer customEngineAiAnalyzer) {
        this.optimizationEngine = optimizationEngine;
        this.historyService = historyService;
        this.customEngineAiAnalyzer = customEngineAiAnalyzer;
    }

    /**
     * Native rule-based analysis only (no custom-engine AI branch). Used internally for reference summaries.
     */
    public QueryAnalysisResponse analyzeNativeOnly(QueryRequest request, boolean expertMode) {
        return analyzeNativeOnly(request, expertMode, resolveUiLocale(request), System.nanoTime());
    }

    QueryAnalysisResponse analyzeNativeOnly(
            QueryRequest request,
            boolean expertMode,
            Locale ui,
            long startNano) {
        return doAnalyzeQueryNative(request, expertMode, ui, startNano);
    }

    private static boolean isCustomDatabaseTypeDeclaration(String declaredLower) {
        return "custom".equals(declaredLower) || declaredLower.startsWith("custom::");
    }

    /**
     * Clave de motor concreta (mysql, mongodb, …) para enrutar el análisis cuando el cliente envía
     * {@code custom} o {@code custom::Nombre::base}.
     */
    private static String resolveEffectiveDatabaseType(QueryRequest request) {
        String raw = request.databaseType();
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String declared = raw.strip().toLowerCase(Locale.ROOT);
        if (!isCustomDatabaseTypeDeclaration(declared)) {
            return declared;
        }
        String explicit = request.customEngineBase();
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip().toLowerCase(Locale.ROOT);
        }
        if (declared.startsWith("custom::")) {
            String[] parts = declared.split("::", -1);
            if (parts.length > 2) {
                String base = parts[parts.length - 1].strip();
                if (!base.isEmpty()) {
                    return base.toLowerCase(Locale.ROOT);
                }
            }
        }
        return "mysql";
    }

    public QueryAnalysisResponse analyzeQuery(QueryRequest request) {
        return analyzeQuery(request, false);
    }

    public QueryAnalysisResponse analyzeQuery(QueryRequest request, boolean expertMode) {
        QueryAnalysisResponse response = doAnalyzeQuery(request, expertMode);
        return attachHistoryEntryId(request, response);
    }

    private QueryAnalysisResponse doAnalyzeQuery(QueryRequest request, boolean expertMode) {
        long startNano = System.nanoTime();
        Locale ui = resolveUiLocale(request);
        String declaredRaw = request.databaseType();
        if (CustomEngineContext.isCustomDeclaration(declaredRaw)) {
            CustomEngineContext ctx = CustomEngineContext.from(request);
            if (customEngineAiAnalyzer.isAvailable()) {
                return customEngineAiAnalyzer.analyze(request, expertMode, ui, startNano);
            }
            return customEngineAiAnalyzer.approximateFallback(request, ctx, ui, startNano);
        }
        return doAnalyzeQueryNative(request, expertMode, ui, startNano);
    }

    private QueryAnalysisResponse doAnalyzeQueryNative(QueryRequest request, boolean expertMode, Locale ui, long startNano) {
        try {
            String query        = request.query();
            String databaseType = resolveEffectiveDatabaseType(request);
            String queryType    = request.queryType()    != null
                    ? request.queryType().toLowerCase(Locale.ROOT)
                    : "";

            boolean isMongo =
                    "mongodb".equals(queryType) || "mongodb".equals(databaseType);
            boolean isRedis =
                    "redis".equals(queryType) || "redis".equals(databaseType);
            boolean isCassandra =
                    "cassandra".equals(queryType) || "cassandra".equals(databaseType);
            boolean isDynamoDb =
                    "dynamodb".equals(queryType) || "dynamodb".equals(databaseType);
            boolean isDynamoExpression =
                    "dynamodb-expression".equalsIgnoreCase(queryType);
            boolean isElasticsearch =
                    "elasticsearch".equals(queryType) || "elasticsearch".equals(databaseType);
            boolean isSql = "sql".equals(queryType) || SQL_DATABASES.contains(databaseType);

            if (isDynamoExpression) {
                if (looksLikeDynamoDbTransactRequest(query)) {
                    var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.DYNAMODB_JSON, startNano, ui);
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    return analyzeDynamoDbTransact(query, startNano, ui, request.connection());
                }
                if (DynamoDbAnalyzer.looksLikeManagementPayload(query)) {
                    var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.DYNAMODB_JSON, startNano, ui);
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    return analyzeDynamoDbManagement(query, startNano, ui, request.connection());
                }
                var blockedExpr = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.DYNAMODB_EXPRESSION, startNano, ui);
                if (blockedExpr.isPresent()) {
                    return blockedExpr.get();
                }
                return analyzeDynamoDbExpression(query, startNano, ui, request.connection());
            }

            if (isElasticsearch) {
                var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.ELASTICSEARCH, startNano, ui);
                if (blocked.isPresent()) {
                    return blocked.get();
                }
                return analyzeElasticsearch(query, startNano, ui, request.connection());
            }

            if (isMongo) {
                var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.MONGODB, startNano, ui);
                if (blocked.isPresent()) {
                    return blocked.get();
                }
                return analyzeMongo(query, startNano, ui, request.connection());
            }

            if (isRedis) {
                var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.REDIS, startNano, ui);
                if (blocked.isPresent()) {
                    return blocked.get();
                }
                return analyzeRedis(query, startNano, ui, request.connection());
            }

            if (isCassandra) {
                var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.CASSANDRA, startNano, ui);
                if (blocked.isPresent()) {
                    return blocked.get();
                }
                return analyzeCassandra(query, startNano, ui, request.connection());
            }

            if (isDynamoDb) {
                if (looksLikeDynamoDbTransactRequest(query)) {
                    var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.DYNAMODB_JSON, startNano, ui);
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    return analyzeDynamoDbTransact(query, startNano, ui, request.connection());
                }
                if (DynamoDbAnalyzer.looksLikeManagementPayload(query)) {
                    var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.DYNAMODB_JSON, startNano, ui);
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    return analyzeDynamoDbManagement(query, startNano, ui, request.connection());
                }
                if (looksLikeDynamoDbSdkExpression(query)) {
                    var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.DYNAMODB_EXPRESSION, startNano, ui);
                    if (blocked.isPresent()) {
                        return blocked.get();
                    }
                    return analyzeDynamoDbExpression(query, startNano, ui, request.connection());
                }
                var blockedPartiql = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.DYNAMODB_PARTIQL, startNano, ui);
                if (blockedPartiql.isPresent()) {
                    return blockedPartiql.get();
                }
                return analyzeDynamoDb(query, startNano, ui, request.connection(), expertMode);
            }

            if (isSql) {
                var blocked = guardWrongEngine(query, CrossEngineSyntaxGuard.TargetEngine.SQL, startNano, ui);
                if (blocked.isPresent()) {
                    return blocked.get();
                }
                return analyzeSql(query, databaseType, request.connection(), startNano, ui, expertMode);
            }

            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            boolean es = isSpanishUi(ui);
            String declaredEngine = request.databaseType() != null ? request.databaseType() : "";
            return new QueryAnalysisResponse(
                    false,
                    List.of(new AnalysisError(
                            "UNKNOWN-ENGINE",
                            es ? "Motor de base de datos no reconocido: " + declaredEngine
                                    : "Unrecognized database engine: " + declaredEngine,
                            es ? "Seleccione un motor soportado en el selector."
                                    : "Choose a supported engine from the dropdown.")),
                    List.of(),
                    List.of(),
                    query,
                    elapsedMs);

        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            String friendlyMsg = friendlyErrorMessage(e.getMessage(), request.query(), ui);
            String sug = isSpanishUi(ui)
                    ? "Revise la consulta y vuelva a intentar."
                    : "Review the query and try again.";
            return new QueryAnalysisResponse(
                    false,
                    List.of(new AnalysisError("PARSE-ERROR", friendlyMsg, sug)),
                    List.of(),
                    List.of(),
                    request.query(),
                    elapsedMs);
        }
    }

    // -------------------------------------------------------------------------
    // Multi-statement analysis (Day 14B — parallel method, never replaces analyzeQuery)
    // -------------------------------------------------------------------------

    /**
     * Analyzes a raw SQL input that may contain multiple statements separated by {@code ;}.
     *
     * <p>Each statement is analyzed independently via {@link #analyzeQuery(QueryRequest)}.
     * The {@code request.query()} field is replaced per-statement; all other request fields
     * (databaseType, queryType, …) are reused as-is.
     *
     * <p>If the input contains only one statement, this method is equivalent to calling
     * {@link #analyzeQuery(QueryRequest)} directly.
     *
     * @param request the original multi-statement request
     * @return a {@link MultiStatementAnalysisResponse} with one entry per statement
     */
    public MultiStatementAnalysisResponse analyzeMultiStatement(QueryRequest request) {
        return analyzeMultiStatement(request, false);
    }

    public MultiStatementAnalysisResponse analyzeMultiStatement(QueryRequest request, boolean expertMode) {
        if (CustomEngineContext.isCustomDeclaration(request.databaseType())) {
            QueryAnalysisResponse single = attachHistoryEntryId(request, doAnalyzeQuery(request, expertMode));
            int health = single.isValid() ? 100 : Math.max(0, 100 - 25 * (single.errors() != null ? single.errors().size() : 0));
            return new MultiStatementAnalysisResponse(
                    List.of(single),
                    single.executionTimeMs(),
                    single,
                    health,
                    single.historyEntryId());
        }
        long startNano = System.nanoTime();
        Locale ui = resolveUiLocale(request);
        String raw = request.query() != null ? request.query() : "";
        String dbType = resolveEffectiveDatabaseType(request);
        String queryType = request.queryType() != null ? request.queryType().toLowerCase(Locale.ROOT) : "";

        SqlDialect splitDialect = resolveDialect(dbType);
        List<String> statements = StatementSplitter.split(raw, splitDialect);

        boolean multi = statements.size() > 1;
        boolean mongoMulti = multi && ("mongodb".equals(dbType) || "mongodb".equals(queryType));
        boolean redisMulti = multi && ("redis".equals(dbType) || "redis".equals(queryType));
        boolean cassandraMulti = multi && ("cassandra".equals(dbType) || "cassandra".equals(queryType));
        boolean esMulti = multi && ("elasticsearch".equals(dbType) || "elasticsearch".equals(queryType));
        boolean dynamoPartiqlMulti = multi
                && ("dynamodb".equals(dbType) || "dynamodb".equals(queryType))
                && !("dynamodb-expression".equalsIgnoreCase(queryType));

        List<QueryAnalysisResponse> results = new ArrayList<>(statements.size());
        for (String stmt : statements) {
            if (mongoMulti) {
                long stmtStart = System.nanoTime();
                var blocked = guardWrongEngine(stmt, CrossEngineSyntaxGuard.TargetEngine.MONGODB, stmtStart, ui);
                if (blocked.isPresent()) {
                    results.add(blocked.get());
                } else {
                    results.add(analyzeMongo(stmt, stmtStart, ui, raw, request.connection()));
                }
            } else if (cassandraMulti) {
                long stmtStart = System.nanoTime();
                results.add(analyzeCassandraMultiFragment(statements, stmt, stmtStart, ui, request.connection()));
            } else if (redisMulti) {
                long stmtStart = System.nanoTime();
                results.add(analyzeRedis(stmt, stmtStart, ui, true, request.connection()));
            } else {
                QueryRequest singleRequest = new QueryRequest(
                        stmt,
                        request.databaseType(),
                        request.queryType(),
                        request.dialect(),
                        request.locale(),
                        request.connection(),
                        request.customEngineBase());
                results.add(doAnalyzeQuery(singleRequest, expertMode));
            }
        }

        // SQL TCL (TCL-001–003) mutates per-statement warnings; only for SQL engines and DynamoDB PartiQL
        // multi-script. Skip for Cassandra/Redis/MongoDB/Elasticsearch — their fragments are not SQL and
        // false positives (e.g. CQL BEGIN BATCH) would appear on the last statement.
        if (statements.size() > 1 && statements.size() == results.size()) {
            if (sqlDatabaseOrQueryType(dbType, queryType) || dynamoPartiqlMulti) {
                applyTransactionalCrossStatementRules(statements, results, dbType);
            }
        }

        List<SemanticError> crossScript = collectCrossScriptSemanticErrors(
                raw,
                dbType,
                queryType,
                statements,
                ui,
                mongoMulti,
                redisMulti,
                cassandraMulti,
                esMulti,
                dynamoPartiqlMulti);

        ScriptLevelSummaryBuilder.BuiltScriptLevel built = ScriptLevelSummaryBuilder.build(
                raw, dbType, statements, results, ui, crossScript);

        long totalMs = (System.nanoTime() - startNano) / 1_000_000;
        QueryAnalysisResponse scriptSummary = built.summary();
        QueryRequest historyRequest = new QueryRequest(
                raw,
                request.databaseType(),
                request.queryType(),
                request.dialect(),
                request.locale(),
                request.connection(),
                request.customEngineBase());
        int errorCount = results.stream()
                .mapToInt(r -> r.errors() != null ? r.errors().size() : 0)
                .sum();
        int warningCount = results.stream()
                .mapToInt(r -> r.warnings() != null ? r.warnings().size() : 0)
                .sum();
        int optCount = results.stream()
                .mapToInt(r -> r.optimizations() != null ? r.optimizations().size() : 0)
                .sum();
        boolean scriptValid = results.stream().allMatch(QueryAnalysisResponse::isValid)
                && (scriptSummary == null || scriptSummary.isValid());
        Optional<Long> historyId = persistHistorySafely(
                historyRequest,
                scriptValid,
                errorCount,
                warningCount,
                optCount,
                new MultiStatementAnalysisResponse(
                        results, totalMs, scriptSummary, built.healthPercent(), null));
        return new MultiStatementAnalysisResponse(
                results, totalMs, scriptSummary, built.healthPercent(), historyId.orElse(null));
    }

    /**
     * Engine-specific findings that require visibility of the full script (not a single split fragment).
     * Extend here when adding cross-statement rules for Redis, Cassandra, DynamoDB, SQL procedural shells, etc.
     */
    private List<SemanticError> collectCrossScriptSemanticErrors(
            String raw,
            String dbType,
            String queryType,
            List<String> statements,
            Locale ui,
            boolean mongoMulti,
            boolean redisMulti,
            boolean cassandraMulti,
            boolean esMulti,
            boolean dynamoPartiqlMulti) {
        List<SemanticError> merged = new ArrayList<>();
        if (mongoMulti) {
            merged.addAll(new MongoDbAnalyzer().analyzeCrossScriptOnly(raw, ui));
        }
        if (redisMulti) {
            merged.addAll(new RedisAnalyzer().analyzeCrossScriptOnly(raw, ui));
        }
        if (cassandraMulti) {
            merged.addAll(new CqlAnalyzer().scriptWideSemanticFindings(statements, ui));
        }
        if (statements.size() > 1 && sqlDatabaseOrQueryType(dbType, queryType)) {
            merged.addAll(collectSqlTclCrossSemanticErrors(
                    statements, resolveDialect(dbType != null ? dbType : ""), ui));
        }
        if (esMulti) {
            merged.addAll(new ElasticsearchAnalyzer().analyzeCrossScriptOnly(raw, ui));
        }
        if (dynamoPartiqlMulti) {
            merged.addAll(collectSqlTclCrossSemanticErrors(statements, SqlDialect.GENERIC, ui));
        }
        return merged;
    }

    private static boolean sqlDatabaseOrQueryType(String dbType, String queryType) {
        if (queryType != null && "sql".equalsIgnoreCase(queryType)) {
            return true;
        }
        return dbType != null && SQL_DATABASES.contains(dbType.toLowerCase(Locale.ROOT));
    }

    private static List<SemanticError> collectSqlTclCrossSemanticErrors(
            List<String> statements, SqlDialect dialect, Locale ui) {
        List<SemanticError> out = new ArrayList<>();
        if (statements == null || statements.size() <= 1) {
            return out;
        }
        int depth = 0;
        Set<String> savepoints = new HashSet<>();

        for (int i = 0; i < statements.size(); i++) {
            AstNode ast;
            try {
                List<Token> tokens = new SqlLexer(statements.get(i), dialect).tokenize();
                ast = new SqlParser(tokens, dialect).parse();
            } catch (Exception ignored) {
                continue;
            }

            switch (ast.getNodeType()) {
                case "BEGIN_TRANSACTION_STATEMENT", "START_TRANSACTION_STATEMENT" -> {
                    if (depth == 0) {
                        savepoints.clear();
                    }
                    depth++;
                }
                case "COMMIT_STATEMENT" -> {
                    if (depth > 0) {
                        depth--;
                        if (depth == 0) {
                            savepoints.clear();
                        }
                    }
                }
                case "ROLLBACK_STATEMENT" -> {
                    boolean toSavepoint = childByType(ast, "SAVEPOINT_REF") != null;
                    if (!toSavepoint) {
                        if (depth == 0) {
                            out.add(new SemanticError(
                                    "TCL-002",
                                    AnalysisMessages.t(ui,
                                            "ROLLBACK without a prior BEGIN or START TRANSACTION in the script",
                                            "ROLLBACK sin BEGIN o START TRANSACTION previo en el script"),
                                    AnalysisMessages.t(ui,
                                            "Open the transaction before rolling back, or remove the ROLLBACK if it does not apply.",
                                            "Abra la transacción antes de revertir, o elimine el ROLLBACK si no aplica."),
                                    SemanticError.Severity.ERROR));
                        } else {
                            depth--;
                            if (depth == 0) {
                                savepoints.clear();
                            }
                        }
                    }
                }
                case "SAVEPOINT_STATEMENT" -> {
                    AstNode nameNode = childByType(ast, "SAVEPOINT_NAME");
                    if (nameNode == null || nameNode.getValue() == null) {
                        break;
                    }
                    String key = nameNode.getValue().toLowerCase(Locale.ROOT);
                    if (!savepoints.add(key)) {
                        out.add(new SemanticError(
                                "TCL-003",
                                AnalysisMessages.t(ui,
                                        "Duplicate SAVEPOINT name in the current transaction block",
                                        "Nombre de SAVEPOINT duplicado en el bloque de transacción actual"),
                                AnalysisMessages.t(ui,
                                        "Use a unique savepoint name within the transaction.",
                                        "Use un nombre de punto de salvamento único dentro de la transacción."),
                                SemanticError.Severity.WARNING));
                    }
                }
                default -> { }
            }
        }

        if (depth > 0) {
            out.add(new SemanticError(
                    "TCL-001",
                    AnalysisMessages.t(ui,
                            "Transaction left open at end of script (missing COMMIT or ROLLBACK)",
                            "Transacción abierta al final del script (falta COMMIT o ROLLBACK)"),
                    AnalysisMessages.t(ui,
                            "Close with COMMIT or ROLLBACK before the script ends, or remove the opening BEGIN/START TRANSACTION.",
                            "Cierre con COMMIT o ROLLBACK antes de terminar el script, o elimine el BEGIN/START TRANSACTION inicial."),
                    SemanticError.Severity.WARNING));
        }
        return out;
    }

    /**
     * Cross-statement transactional checks (TCL-001–TCL-003) for scripts with several statements.
     * Each fragment is re-parsed; failures are ignored for this pass.
     *
     * <p>Invoked only from {@link #analyzeMultiStatement} when {@link #sqlDatabaseOrQueryType} holds or
     * the script is DynamoDB PartiQL multi-statement — never for other NoSQL engines.
     */
    private void applyTransactionalCrossStatementRules(
            List<String> statements,
            List<QueryAnalysisResponse> results,
            String databaseType) {
        if (statements.size() <= 1 || statements.size() != results.size()) {
            return;
        }

        SqlDialect dialect = resolveDialect(
                databaseType != null ? databaseType.toLowerCase(Locale.ROOT) : "");

        int depth = 0;
        Set<String> savepoints = new HashSet<>();

        for (int i = 0; i < statements.size(); i++) {
            AstNode ast;
            try {
                List<Token> tokens = new SqlLexer(statements.get(i), dialect).tokenize();
                ast = new SqlParser(tokens, dialect).parse();
            } catch (Exception ignored) {
                continue;
            }

            switch (ast.getNodeType()) {
                case "BEGIN_TRANSACTION_STATEMENT", "START_TRANSACTION_STATEMENT" -> {
                    if (depth == 0) {
                        savepoints.clear();
                    }
                    depth++;
                }
                case "COMMIT_STATEMENT" -> {
                    if (depth > 0) {
                        depth--;
                        if (depth == 0) {
                            savepoints.clear();
                        }
                    }
                }
                case "ROLLBACK_STATEMENT" -> {
                    boolean toSavepoint = childByType(ast, "SAVEPOINT_REF") != null;
                    if (!toSavepoint) {
                        if (depth == 0) {
                            results.set(i, withExtraErrors(
                                    results.get(i),
                                    new AnalysisError(
                                            "TCL-002",
                                            "ROLLBACK sin BEGIN o START TRANSACTION previo en el script",
                                            "Revise el orden: abra la transacción antes de revertir, o elimine el ROLLBACK si no aplica.")));
                        } else {
                            depth--;
                            if (depth == 0) {
                                savepoints.clear();
                            }
                        }
                    }
                }
                case "SAVEPOINT_STATEMENT" -> {
                    AstNode nameNode = childByType(ast, "SAVEPOINT_NAME");
                    if (nameNode == null || nameNode.getValue() == null) {
                        break;
                    }
                    String key = nameNode.getValue().toLowerCase(Locale.ROOT);
                    if (!savepoints.add(key)) {
                        results.set(i, withExtraWarnings(results.get(i), "TCL-003"));
                    }
                }
                default -> { }
            }
        }

        if (depth > 0) {
            int last = results.size() - 1;
            results.set(last, withExtraWarnings(results.get(last), "TCL-001"));
        }
    }

    private static AstNode childByType(AstNode parent, String type) {
        for (AstNode c : parent.getChildren()) {
            if (type.equals(c.getNodeType())) {
                return c;
            }
        }
        return null;
    }

    private static QueryAnalysisResponse withExtraWarnings(QueryAnalysisResponse r, String code) {
        List<AnalysisWarning> warnings = new ArrayList<>(r.warnings());
        warnings.add(new AnalysisWarning(code, SemanticError.Severity.WARNING.name()));
        return new QueryAnalysisResponse(
                r.errors().isEmpty(),
                r.errors(),
                warnings,
                r.optimizations(),
                r.analyzedQuery(),
                r.executionTimeMs(),
                r.astTree(),
                r.metrics(),
                r.metadata(),
                r.historyEntryId());
    }

    private static QueryAnalysisResponse withExtraErrors(QueryAnalysisResponse r, AnalysisError err) {
        List<AnalysisError> errors = new ArrayList<>(r.errors());
        errors.add(err);
        return new QueryAnalysisResponse(
                false,
                errors,
                r.warnings(),
                r.optimizations(),
                r.analyzedQuery(),
                r.executionTimeMs(),
                r.astTree(),
                r.metrics(),
                r.metadata(),
                r.historyEntryId());
    }

    private QueryAnalysisResponse attachHistoryEntryId(QueryRequest request, QueryAnalysisResponse result) {
        Optional<Long> id = persistHistorySafely(request, result);
        return id.map(result::withHistoryEntryId).orElse(result);
    }

    private Optional<Long> persistHistorySafely(QueryRequest request, QueryAnalysisResponse result) {
        return persistHistorySafely(
                request,
                result.isValid(),
                result.errors() != null ? result.errors().size() : 0,
                result.warnings() != null ? result.warnings().size() : 0,
                result.optimizations() != null ? result.optimizations().size() : 0,
                result);
    }

    private Optional<Long> persistHistorySafely(
            QueryRequest request,
            boolean valid,
            int errorCount,
            int warningCount,
            int optimizationCount,
            Object analysisResult) {
        try {
            String dbType = request.databaseType() != null ? request.databaseType() : "";
            String locale = request.locale() != null ? request.locale().trim() : null;
            return historyService.save(
                    request.query(),
                    dbType,
                    valid,
                    errorCount,
                    warningCount,
                    optimizationCount,
                    analysisResult,
                    locale);
        } catch (Exception e) {
            log.warn("Failed to save query history: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // MongoDB pipeline
    // -------------------------------------------------------------------------

    private QueryAnalysisResponse analyzeMongo(String query, long startNano, Locale ui, DatabaseConfig connection) {
        return analyzeMongo(query, startNano, ui, null, connection);
    }

    /**
     * @param fullScript when non-null and this call is part of multi-statement MongoDB analysis,
     *                    cross-script transaction hints use the full script; the body uses {@link MongoDbAnalyzer#analyzeFragment}.
     */
    private QueryAnalysisResponse analyzeMongo(
            String query, long startNano, Locale ui, String fullScript, DatabaseConfig connection) {
        MongoDbAnalyzer mongoAnalyzer = new MongoDbAnalyzer();
        List<SemanticError> delegateFindings = fullScript != null
                ? mongoAnalyzer.analyzeFragment(query, ui, fullScript)
                : mongoAnalyzer.analyze(query, ui);
        List<SemanticError> findings = connection != null
                ? LiveSchemaEnrichment.enrich(
                        delegateFindings, query, connection, ui, LiveSchemaEnrichment.Engine.MONGODB)
                : delegateFindings;

        List<AnalysisError> errors = findings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList();

        List<AnalysisWarning> warnings = warningsFrom(findings);

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(errors.isEmpty(), errors, warnings, List.of(), query, elapsedMs);
    }

    // -------------------------------------------------------------------------
    // Apache Cassandra (CQL) pipeline
    // -------------------------------------------------------------------------

    private QueryAnalysisResponse analyzeCassandra(String query, long startNano, Locale ui, DatabaseConfig connection) {
        CqlAnalyzer analyzer = new CqlAnalyzer();
        List<SemanticError> delegateFindings = analyzer.analyze(query, ui);
        List<SemanticError> findings = connection != null
                ? LiveSchemaEnrichment.enrich(
                        delegateFindings, query, connection, ui, LiveSchemaEnrichment.Engine.CASSANDRA)
                : delegateFindings;

        List<AnalysisError> errors = findings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList();

        List<AnalysisWarning> warnings = warningsFrom(findings);

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(errors.isEmpty(), errors, warnings, List.of(), query, elapsedMs);
    }

    /**
     * One fragment of a multi-statement CQL script, using CREATE/schema context from the full script.
     */
    private QueryAnalysisResponse analyzeCassandraMultiFragment(
            List<String> allStatements, String stmt, long startNano, Locale ui, DatabaseConfig connection) {
        CqlAnalyzer analyzer = new CqlAnalyzer();
        List<SemanticError> delegateFindings =
                analyzer.ingestFullScriptAndAnalyzeOne(allStatements, stmt, ui);
        List<SemanticError> findings = connection != null
                ? LiveSchemaEnrichment.enrich(
                        delegateFindings, stmt, connection, ui, LiveSchemaEnrichment.Engine.CASSANDRA)
                : delegateFindings;

        List<AnalysisError> errors = findings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList();

        List<AnalysisWarning> warnings = warningsFrom(findings);

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(errors.isEmpty(), errors, warnings, List.of(), stmt, elapsedMs);
    }

    // -------------------------------------------------------------------------
    // Amazon DynamoDB — Expression syntax (SDK low-level API strings)
    // -------------------------------------------------------------------------

    private QueryAnalysisResponse analyzeDynamoDbExpression(
            String rawPayload, long startNano, Locale ui, DatabaseConfig connection) {
        if (looksLikeDynamoDbTransactRequest(rawPayload)) {
            return analyzeDynamoDbTransact(rawPayload, startNano, ui, connection);
        }
        try {
            DynamoDbExpressionPayload payload = DynamoDbExpressionPayload.parse(rawPayload);
            DynamoDbExpressionAnalyzer analyzer = new DynamoDbExpressionAnalyzer();
            List<SemanticError> delegateFindings = analyzer.analyze(payload, ui);
            List<SemanticError> findings = connection != null
                    ? LiveSchemaEnrichment.enrich(
                            delegateFindings, rawPayload, connection, ui,
                            LiveSchemaEnrichment.Engine.DYNAMODB_EXPRESSION)
                    : delegateFindings;

            List<AnalysisError> errors = findings.stream()
                    .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                    .map(QueryAnalysisService::toApiError)
                    .toList();

            List<AnalysisWarning> warnings = warningsFrom(findings);

            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            return new QueryAnalysisResponse(errors.isEmpty(), errors, warnings, List.of(), payload.expression(), elapsedMs);
        } catch (IllegalArgumentException e) {
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            boolean es = isSpanishUi(ui);
            return new QueryAnalysisResponse(
                    false,
                    List.of(new AnalysisError(
                            "DDB-EXPR-INPUT",
                            e.getMessage() != null ? e.getMessage()
                                    : (es ? "Carga JSON de expresión DynamoDB no válida."
                                            : "Invalid DynamoDB expression payload"),
                            es ? "Envía JSON con kind, expression y opcionalmente expressionAttributeNames/Values."
                                    : "Send JSON with kind, expression, and optional expressionAttributeNames/Values maps.")),
                    List.of(),
                    List.of(),
                    rawPayload,
                    elapsedMs);
        }
    }

    // -------------------------------------------------------------------------
    // Amazon DynamoDB — TransactWriteItems / TransactGetItems (JSON body)
    // -------------------------------------------------------------------------

    private static boolean looksLikeDynamoDbTransactRequest(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String s = query.strip();
        if (!s.startsWith("{")) {
            return false;
        }
        try {
            JsonNode root = DDB_JSON_ROUTER.readTree(s);
            JsonNode items = root.get("TransactItems");
            return items != null && items.isArray();
        } catch (Exception e) {
            return false;
        }
    }

    private QueryAnalysisResponse analyzeDynamoDbTransact(
            String query, long startNano, Locale ui, DatabaseConfig connection) {
        DynamoDbAnalyzer analyzer = new DynamoDbAnalyzer();
        List<DynamoDbSemanticError> ddbFindings = analyzer.analyzeTransactRequest(query, ui);

        List<SemanticError> schemaFindings = connection != null
                ? LiveSchemaEnrichment.enrich(
                        List.of(), query, connection, ui, LiveSchemaEnrichment.Engine.DYNAMODB_TRANSACT)
                : List.of();

        List<AnalysisError> errors = new ArrayList<>(ddbFindings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(e -> toApiError(e, ui))
                .toList());
        errors.addAll(schemaFindings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList());
        boolean isValid = errors.isEmpty();

        List<AnalysisWarning> warnings = mergeWarnings(
                warningsFromDdb(ddbFindings), warningsFrom(schemaFindings));

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(isValid, errors, warnings, List.of(), query, elapsedMs);
    }

    /** Streams, backup/restore, PITR, Lambda on stream ({@code DDB-STR-*}, {@code DDB-BAK-*}). */
    private QueryAnalysisResponse analyzeDynamoDbManagement(
            String query, long startNano, Locale ui, DatabaseConfig connection) {
        DynamoDbAnalyzer analyzer = new DynamoDbAnalyzer();
        List<DynamoDbSemanticError> ddbFindings = analyzer.analyzeManagementPayload(query, ui);

        List<SemanticError> schemaFindings = connection != null
                ? LiveSchemaEnrichment.enrich(
                        List.of(), query, connection, ui, LiveSchemaEnrichment.Engine.DYNAMODB_MANAGEMENT)
                : List.of();

        List<AnalysisError> errors = new ArrayList<>(ddbFindings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(e -> toApiError(e, ui))
                .toList());
        errors.addAll(schemaFindings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList());
        boolean isValid = errors.isEmpty();

        List<AnalysisWarning> warnings = mergeWarnings(
                warningsFromDdb(ddbFindings), warningsFrom(schemaFindings));

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(isValid, errors, warnings, List.of(), query, elapsedMs);
    }

    // -------------------------------------------------------------------------
    // Amazon DynamoDB (PartiQL) — SQL lexer/parser + DynamoDbAnalyzer
    // -------------------------------------------------------------------------

    private QueryAnalysisResponse analyzeDynamoDb(
            String query, long startNano, Locale ui, DatabaseConfig connection, boolean expertMode) {
        SqlDialect dialect = SqlDialect.GENERIC;

        long t0 = System.currentTimeMillis();
        List<Token> tokens = new SqlLexer(query, dialect).tokenize();
        long lexTime = System.currentTimeMillis() - t0;
        long t1 = System.currentTimeMillis();

        SqlParser parser = new SqlParser(tokens, dialect);
        AstNode ast;
        List<AnalysisError> hardSyntaxErrors = new ArrayList<>();
        try {
            ast = parser.parse();
        } catch (SqlParser.ParseException pe) {
            long parseTime = System.currentTimeMillis() - t1;
            int[] lc      = parseLineCol(pe.getMessage());
            Integer ln    = lc != null ? lc[0] : null;
            Integer col   = lc != null ? lc[1] : null;
            String friendly = friendlyErrorMessage(pe.getMessage(), query, ui);
            hardSyntaxErrors.add(new AnalysisError("SYN-001-PARTIQL", friendly,
                    synPartiqlSuggestion(ui), ln, col));
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            return buildErrorResponse(
                    hardSyntaxErrors,
                    query,
                    elapsedMs,
                    expertMode,
                    null,
                    expertMetrics(lexTime, parseTime, 0, tokens.size(), 0, 0));
        }
        long parseTime = System.currentTimeMillis() - t1;
        long t2 = System.currentTimeMillis();

        List<AnalysisError> syntaxErrors = AnalysisErrors.fromSqlParser(
                parser,
                "SYN-001-PARTIQL",
                synPartiqlSuggestion(ui));

        DynamoDbAnalyzer analyzer = new DynamoDbAnalyzer();
        List<DynamoDbSemanticError> findings = analyzer.analyze(ast, query, ui);
        long semTime = System.currentTimeMillis() - t2;

        List<AnalysisError> errors = new ArrayList<>(syntaxErrors);
        errors.addAll(findings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(e -> toApiError(e, ui))
                .toList());
        List<SemanticError> schemaFindings = connection != null
                ? LiveSchemaEnrichment.enrich(
                        List.of(), query, connection, ui, LiveSchemaEnrichment.Engine.DYNAMODB_PARTIQL, ast)
                : List.of();
        errors.addAll(schemaFindings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList());
        boolean isValid = errors.isEmpty();

        List<AnalysisWarning> warnings = mergeWarnings(
                warningsFromDdb(findings), warningsFrom(schemaFindings));

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(
                isValid,
                errors,
                warnings,
                List.of(),
                query,
                elapsedMs,
                expertMode ? AstNodeDto.from(ast) : null,
                expertMode
                        ? expertMetrics(lexTime, parseTime, semTime, tokens.size(), astDepth(ast), 0)
                        : null);
    }

    /**
     * Routes Day 22 SDK expression strings while keeping Day 21 PartiQL on {@link #analyzeDynamoDb}.
     * Explicit {@code queryType=dynamodb-expression} always uses {@link #analyzeDynamoDbExpression}
     * before this branch runs.
     */
    private static boolean looksLikeDynamoDbSdkExpression(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String s = query.strip();
        String u = s.toUpperCase(Locale.ROOT);

        if (u.startsWith("SELECT")) {
            return false;
        }
        if (u.startsWith("INSERT")) {
            return false;
        }
        if (u.startsWith("DELETE FROM")) {
            return false;
        }
        if (u.startsWith("UPDATE ")) {
            return !isDynamoDbPartiQlUpdate(s);
        }
        if (s.indexOf('#') >= 0) {
            return true;
        }
        if (DDB_SDK_UPDATE_LEADING.matcher(s).find()) {
            return true;
        }
        if (containsDynamoDbSdkBuiltinCall(u)) {
            return true;
        }
        if (!u.contains(" FROM ") && s.contains(":") && DDB_SDK_CONDITION_OPS.matcher(s).find()) {
            return true;
        }
        return false;
    }

    private static boolean isDynamoDbPartiQlUpdate(String s) {
        return DDB_PARTIQL_UPDATE.matcher(s).find();
    }

    private static boolean containsDynamoDbSdkBuiltinCall(String u) {
        return u.contains("ATTRIBUTE_EXISTS(")
                || u.contains("ATTRIBUTE_NOT_EXISTS(")
                || u.contains("ATTRIBUTE_TYPE(")
                || u.contains("BEGINS_WITH(")
                || u.contains("IF_NOT_EXISTS(")
                || u.contains("LIST_APPEND(")
                || u.contains("SIZE(");
    }

    // -------------------------------------------------------------------------
    // Elasticsearch Query DSL (JSON)
    // -------------------------------------------------------------------------

    private QueryAnalysisResponse analyzeElasticsearch(
            String query, long startNano, Locale ui, DatabaseConfig connection) {
        ElasticsearchAnalyzer esAnalyzer = new ElasticsearchAnalyzer();
        List<SemanticError> delegateFindings = esAnalyzer.analyze(query, ui);
        String targetIndex = connection != null ? connection.database() : null;
        List<SemanticError> findings = connection != null
                ? LiveSchemaEnrichment.enrich(
                        delegateFindings,
                        query,
                        connection,
                        ui,
                        LiveSchemaEnrichment.Engine.ELASTICSEARCH,
                        null,
                        targetIndex)
                : delegateFindings;

        List<AnalysisError> errors = findings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList();

        List<AnalysisWarning> warnings = warningsFrom(findings);

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(errors.isEmpty(), errors, warnings, List.of(), query, elapsedMs);
    }

    // -------------------------------------------------------------------------
    // Redis pipeline
    // -------------------------------------------------------------------------

    private QueryAnalysisResponse analyzeRedis(String query, long startNano, Locale ui, DatabaseConfig connection) {
        return analyzeRedis(query, startNano, ui, false, connection);
    }

    private QueryAnalysisResponse analyzeRedis(
            String query, long startNano, Locale ui, boolean multiStatementFragment, DatabaseConfig connection) {
        RedisAnalyzer analyzer = new RedisAnalyzer();
        List<SemanticError> delegateFindings = multiStatementFragment
                ? analyzer.analyzeForMultiStatementFragment(query, ui)
                : analyzer.analyze(query, ui);
        List<SemanticError> findings = connection != null
                ? LiveSchemaEnrichment.enrich(
                        delegateFindings, query, connection, ui, LiveSchemaEnrichment.Engine.REDIS)
                : delegateFindings;

        List<AnalysisError> errors = findings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList();

        List<AnalysisWarning> warnings = warningsFrom(findings);

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        return new QueryAnalysisResponse(errors.isEmpty(), errors, warnings, List.of(), query, elapsedMs);
    }

    // -------------------------------------------------------------------------
    // SQL pipeline
    // -------------------------------------------------------------------------

    private QueryAnalysisResponse analyzeSql(
            String query,
            String databaseType,
            DatabaseConfig connection,
            long startNano,
            Locale ui,
            boolean expertMode) {
        SqlDialect splitDialect = resolveDialect(databaseType);
        if (mysqlScriptNeedsStatementSplitting(query, databaseType)) {
            List<String> parts = StatementSplitter.split(query, splitDialect);
            if (parts.size() > 1) {
                return mergeMysqlScriptFragments(parts, query, databaseType, connection, startNano, ui, expertMode);
            }
        }
        return analyzeSingleSqlStatement(query, databaseType, connection, ui, expertMode);
    }

    /**
     * MySQL scripts with {@code DELIMITER} or several statements — split and merge results (Day 14L).
     */
    private boolean mysqlScriptNeedsStatementSplitting(String query, String databaseType) {
        if (query == null || databaseType == null) {
            return false;
        }
        if (!"mysql".equalsIgnoreCase(databaseType)) {
            return false;
        }
        if (query.toUpperCase(Locale.ROOT).contains("DELIMITER")) {
            return true;
        }
        return StatementSplitter.split(query, SqlDialect.MYSQL).size() > 1;
    }

    private QueryAnalysisResponse mergeMysqlScriptFragments(
            List<String> fragments,
            String originalQuery,
            String databaseType,
            DatabaseConfig connection,
            long startNano,
            Locale ui,
            boolean expertMode) {
        List<AnalysisError> allErrors = new ArrayList<>();
        List<AnalysisWarning> allWarnings = new ArrayList<>();
        List<OptimizationSuggestion> allOpts = new ArrayList<>();
        List<QueryAnalysisResponse> fragmentResults = new ArrayList<>(fragments.size());
        boolean allValid = true;
        int idx = 1;
        for (String frag : fragments) {
            QueryAnalysisResponse r = analyzeSingleSqlStatement(frag, databaseType, connection, ui, expertMode);
            fragmentResults.add(r);
            if (!r.isValid()) {
                allValid = false;
            }
            for (AnalysisError e : r.errors()) {
                allErrors.add(new AnalysisError(
                        e.code(),
                        "[Stmt " + idx + "] " + e.message(),
                        e.suggestion(),
                        e.line(),
                        e.column()));
            }
            allWarnings.addAll(r.warnings());
            allOpts.addAll(r.optimizations());
            idx++;
        }
        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
        if (!expertMode) {
            return new QueryAnalysisResponse(allValid, allErrors, allWarnings, allOpts, originalQuery, elapsedMs);
        }
        return new QueryAnalysisResponse(
                allValid,
                allErrors,
                allWarnings,
                allOpts,
                originalQuery,
                elapsedMs,
                pickLastAstTree(fragmentResults),
                mergeExpertMetrics(fragmentResults));
    }

    private QueryAnalysisResponse analyzeSingleSqlStatement(
            String query, String databaseType, DatabaseConfig connection, Locale ui, boolean expertMode) {
        long startNano = System.nanoTime();
        SqlDialect dialect = resolveDialect(databaseType);

        long t0 = System.currentTimeMillis();
        List<Token> tokens = new SqlLexer(query, dialect).tokenize();
        long lexTime = System.currentTimeMillis() - t0;
        long t1 = System.currentTimeMillis();

        SqlParser parser = new SqlParser(tokens, dialect);
        AstNode ast;
        List<AnalysisError> hardSyntaxErrors = new ArrayList<>();
        try {
            ast = parser.parse();
        } catch (SqlParser.ParseException pe) {
            long parseTime = System.currentTimeMillis() - t1;
            int[] lc      = parseLineCol(pe.getMessage());
            Integer ln    = lc != null ? lc[0] : null;
            Integer col   = lc != null ? lc[1] : null;
            String friendly = friendlyErrorMessage(pe.getMessage(), query, ui);
            hardSyntaxErrors.add(new AnalysisError("SYN-001-SQL", friendly,
                    synSqlSuggestion(ui), ln, col));
            long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;
            return buildErrorResponse(
                    hardSyntaxErrors,
                    query,
                    elapsedMs,
                    expertMode,
                    null,
                    expertMetrics(lexTime, parseTime, 0, tokens.size(), 0, 0));
        }
        long parseTime = System.currentTimeMillis() - t1;
        long t2 = System.currentTimeMillis();

        List<AnalysisError> syntaxErrors = AnalysisErrors.fromSqlParser(
                parser,
                "SYN-001-SQL",
                synSqlSuggestion(ui));

        SemanticAnalyzer analyzer = createAnalyzer(ast, query, dialect, ui, connection, databaseType);
        List<SemanticError> findings   = new ArrayList<>(analyzer.analyze());
        if (isProceduralAstRoot(ast)) {
            findings.addAll(ProceduralSemanticAnalyzer.analyze(ast, query, ui));
        }
        long semTime = System.currentTimeMillis() - t2;

        List<String> schemaColumns = List.of();
        if (connection != null && isSqlDatabaseType(databaseType)) {
            schemaColumns = SchemaOptimizationSupport.resolvePrimaryTableColumnNames(ast, connection);
        }
        OptimizationResult optResult =
                optimizationEngine.optimizeWithMetrics(ast, query, dialect, schemaColumns);

        List<AnalysisError> errors = new ArrayList<>(syntaxErrors);
        errors.addAll(findings.stream()
                .filter(e -> e.severity() == SemanticError.Severity.ERROR)
                .map(QueryAnalysisService::toApiError)
                .toList());
        boolean isValid = errors.isEmpty();

        List<AnalysisWarning> warnings = warningsFrom(findings);

        long elapsedMs = (System.nanoTime() - startNano) / 1_000_000;

        return new QueryAnalysisResponse(
                isValid,
                errors,
                warnings,
                optResult.suggestions(),
                query,
                elapsedMs,
                expertMode ? AstNodeDto.from(ast) : null,
                expertMode
                        ? expertMetrics(
                                lexTime,
                                parseTime,
                                semTime,
                                tokens.size(),
                                astDepth(ast),
                                optResult.rulesEvaluated())
                        : null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Visible to {@link ScriptLevelSummaryBuilder} for cross-script MongoDB (and future engines). */
    public static AnalysisError toApiError(SemanticError e) {
        return new AnalysisError(e.code(), e.message(), e.suggestion(), e.line(), e.column());
    }

    private static AnalysisError toApiError(DynamoDbSemanticError e, Locale ui) {
        return new AnalysisError(e.code(), e.message(), e.suggestionForApi(ui), null, null);
    }

    private static QueryAnalysisResponse buildErrorResponse(
            List<AnalysisError> errors, String query, long elapsedMs) {
        return buildErrorResponse(errors, query, elapsedMs, false, null, null);
    }

    private static QueryAnalysisResponse buildErrorResponse(
            List<AnalysisError> errors,
            String query,
            long elapsedMs,
            boolean expertMode,
            AstNodeDto astTree,
            AnalysisMetricsDto metrics) {
        return new QueryAnalysisResponse(
                false,
                errors,
                List.of(),
                List.of(),
                query,
                elapsedMs,
                expertMode ? astTree : null,
                expertMode ? metrics : null);
    }

    private static AnalysisMetricsDto expertMetrics(
            long lexingTimeMs,
            long parsingTimeMs,
            long semanticTimeMs,
            int totalTokens,
            int astDepth,
            int optimizationRulesEvaluated) {
        return new AnalysisMetricsDto(
                lexingTimeMs,
                parsingTimeMs,
                semanticTimeMs,
                totalTokens,
                astDepth,
                optimizationRulesEvaluated);
    }

    private static AstNodeDto pickLastAstTree(List<QueryAnalysisResponse> fragments) {
        AstNodeDto last = null;
        for (QueryAnalysisResponse r : fragments) {
            if (r.astTree() != null) {
                last = r.astTree();
            }
        }
        return last;
    }

    private static AnalysisMetricsDto mergeExpertMetrics(List<QueryAnalysisResponse> fragments) {
        long lex = 0;
        long parse = 0;
        long sem = 0;
        int tokens = 0;
        int depth = 0;
        int rules = 0;
        boolean any = false;
        for (QueryAnalysisResponse r : fragments) {
            AnalysisMetricsDto m = r.metrics();
            if (m == null) {
                continue;
            }
            any = true;
            lex += m.lexingTimeMs();
            parse += m.parsingTimeMs();
            sem += m.semanticTimeMs();
            tokens += m.totalTokens();
            depth = Math.max(depth, m.astDepth());
            rules += m.optimizationRulesEvaluated();
        }
        return any ? expertMetrics(lex, parse, sem, tokens, depth, rules) : null;
    }

    private static int astDepth(AstNode node) {
        if (node == null) {
            return 0;
        }
        int maxChild = 0;
        for (AstNode child : node.getChildren()) {
            maxChild = Math.max(maxChild, astDepth(child));
        }
        return 1 + maxChild;
    }

    private SqlDialect resolveDialect(String databaseType) {
        return switch (databaseType) {
            case "mysql"      -> SqlDialect.MYSQL;
            case "postgresql" -> SqlDialect.POSTGRESQL;
            case "sqlite"     -> SqlDialect.SQLITE;
            case "sqlserver"  -> SqlDialect.SQLSERVER;
            case "oracle"     -> SqlDialect.ORACLE;
            default           -> SqlDialect.GENERIC;
        };
    }

    private SemanticAnalyzer createAnalyzer(
            AstNode ast,
            String rawSql,
            SqlDialect dialect,
            Locale ui,
            DatabaseConfig connection,
            String databaseType) {
        SemanticAnalyzer base;
        if (dialect == SqlDialect.ORACLE && isOracleProceduralContext(ast, rawSql)) {
            base = new PlSqlAnalyzer(ast, rawSql, null, ui);
        } else if (dialect == SqlDialect.SQLSERVER && isSqlServerProceduralContext(ast, rawSql)) {
            base = new TSqlAnalyzer(ast, rawSql, ui);
        } else if (dialect == SqlDialect.POSTGRESQL && isPostgreSqlProceduralContext(ast, rawSql)) {
            base = new PlPgSqlAnalyzer(ast, rawSql, ui);
        } else if (dialect == SqlDialect.MYSQL && isMysqlProceduralContext(ast, rawSql)) {
            base = new MySqlPsmAnalyzer(ast, rawSql, ui);
        } else {
            base = switch (dialect) {
                case MYSQL      -> new MySQLDialectAnalyzer(ast, rawSql, ui);
                case POSTGRESQL -> new PostgreSQLDialectAnalyzer(ast, rawSql, ui);
                case SQLITE     -> new SQLiteDialectAnalyzer(ast, rawSql, ui);
                case SQLSERVER  -> new SqlServerDialectAnalyzer(ast, rawSql, ui);
                case ORACLE     -> new OracleDialectAnalyzer(ast, rawSql, ui);
                default         -> new SemanticAnalyzer(ast, rawSql, ui);
            };
        }
        if (connection != null && isSqlDatabaseType(databaseType)) {
            return new SchemaAwareSemanticAnalyzer(base, connection);
        }
        return base;
    }

    private static boolean isSqlDatabaseType(String databaseType) {
        if (databaseType == null || databaseType.isBlank()) {
            return false;
        }
        return SQL_DATABASES.contains(databaseType.toLowerCase(Locale.ROOT));
    }

    private static boolean isProceduralAstRoot(AstNode ast) {
        if (ast == null) {
            return false;
        }
        return switch (ast.getNodeType()) {
            case "BLOCK_STATEMENT",
                 "CREATE_PROCEDURE_STATEMENT",
                 "CREATE_FUNCTION_STATEMENT",
                 "CREATE_TRIGGER_STATEMENT" -> true;
            default -> false;
        };
    }

    /**
     * True when the statement is PL/SQL–style procedural (native Oracle analysis via {@link PlSqlAnalyzer}).
     */
    private static boolean isOracleProceduralContext(AstNode ast, String rawSql) {
        String t = ast.getNodeType();
        if (t != null) {
            switch (t) {
                case "BLOCK_STATEMENT",
                     "CREATE_PROCEDURE_STATEMENT",
                     "CREATE_FUNCTION_STATEMENT",
                     "CREATE_TRIGGER_STATEMENT",
                     "CREATE_PACKAGE_STATEMENT",
                     "CREATE_PACKAGE_BODY_STATEMENT",
                     "IF_STATEMENT",
                     "WHILE_STATEMENT",
                     "LOOP_STATEMENT",
                     "FOR_STATEMENT",
                     "CASE_STATEMENT",
                     "OPEN_CURSOR_STATEMENT",
                     "FETCH_STATEMENT",
                     "CLOSE_CURSOR_STATEMENT",
                     "RAISE_STATEMENT",
                     "EXIT_WHEN_STATEMENT" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        if (rawSql != null) {
            String s = rawSql.stripLeading();
            String u = s.toUpperCase(Locale.ROOT);
            if (u.startsWith("DECLARE")) {
                return true;
            }
            if (u.startsWith("BEGIN")) {
                if (u.startsWith("BEGIN TRANSACTION")
                        || u.startsWith("BEGIN WORK")
                        || u.startsWith("BEGIN ISOLATION")) {
                    return false;
                }
                return true;
            }
            if (u.contains("CREATE ") && (u.contains(" PROCEDURE")
                    || u.contains("\nPROCEDURE")
                    || u.contains(" FUNCTION")
                    || u.contains("\nFUNCTION")
                    || u.contains(" TRIGGER")
                    || u.contains("\nTRIGGER")
                    || u.contains(" PACKAGE")
                    || u.contains("\nPACKAGE"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the statement is T-SQL–style procedural (native analysis via {@link TSqlAnalyzer}).
     */
    private static boolean isSqlServerProceduralContext(AstNode ast, String rawSql) {
        String t = ast.getNodeType();
        if (t != null) {
            switch (t) {
                case "BLOCK_STATEMENT",
                     "CREATE_PROCEDURE_STATEMENT",
                     "CREATE_FUNCTION_STATEMENT",
                     "CREATE_TRIGGER_STATEMENT",
                     "THROW_STATEMENT",
                     "DEALLOCATE_CURSOR_STATEMENT",
                     "GOTO_STATEMENT",
                     "WAITFOR_STATEMENT",
                     "EXEC_STATEMENT",
                     "IF_STATEMENT",
                     "WHILE_STATEMENT",
                     "LOOP_STATEMENT",
                     "FOR_STATEMENT",
                     "CASE_STATEMENT",
                     "OPEN_CURSOR_STATEMENT",
                     "FETCH_STATEMENT",
                     "CLOSE_CURSOR_STATEMENT",
                     "RAISE_STATEMENT",
                     "RAISERROR_STATEMENT",
                     "EXIT_WHEN_STATEMENT" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        if (rawSql != null) {
            String s = rawSql.stripLeading();
            String u = s.toUpperCase(Locale.ROOT);
            if (u.startsWith("DECLARE")) {
                return true;
            }
            if (u.startsWith("BEGIN")) {
                if (u.startsWith("BEGIN TRANSACTION")
                        || u.startsWith("BEGIN WORK")
                        || u.startsWith("BEGIN ISOLATION")) {
                    return false;
                }
                return true;
            }
            if (u.startsWith("THROW")
                    || u.startsWith("EXEC ")
                    || u.startsWith("EXECUTE ")
                    || u.startsWith("DEALLOCATE")
                    || u.startsWith("WAITFOR")
                    || u.startsWith("GOTO ")) {
                return true;
            }
            if (u.contains("CREATE ") && (u.contains(" PROCEDURE")
                    || u.contains("\nPROCEDURE")
                    || u.contains(" PROC ")
                    || u.contains("\nPROC ")
                    || u.contains(" FUNCTION")
                    || u.contains("\nFUNCTION")
                    || u.contains(" TRIGGER")
                    || u.contains("\nTRIGGER"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the statement is PL/pgSQL–style procedural (PostgreSQL native analysis via {@link PlPgSqlAnalyzer}).
     */
    private static boolean isPostgreSqlProceduralContext(AstNode ast, String rawSql) {
        String t = ast.getNodeType();
        if (t != null) {
            switch (t) {
                case "BLOCK_STATEMENT",
                        "CREATE_FUNCTION_STATEMENT",
                        "CREATE_TRIGGER_STATEMENT",
                        "IF_STATEMENT",
                        "WHILE_STATEMENT",
                        "LOOP_STATEMENT",
                        "FOR_STATEMENT",
                        "CASE_STATEMENT",
                        "OPEN_CURSOR_STATEMENT",
                        "FETCH_STATEMENT",
                        "CLOSE_CURSOR_STATEMENT",
                        "RAISE_STATEMENT",
                        "EXIT_WHEN_STATEMENT",
                        "RETURN_QUERY_STATEMENT",
                        "RETURN_NEXT_STATEMENT",
                        "RETURN_STATEMENT",
                        "EXECUTE_STATEMENT",
                        "PERFORM_STATEMENT",
                        "GET_DIAGNOSTICS_STATEMENT",
                        "NOTIFY_STATEMENT",
                        "LISTEN_STATEMENT",
                        "DO_STATEMENT" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        if (rawSql != null) {
            String s = rawSql.stripLeading();
            String u = s.toUpperCase(Locale.ROOT);
            if (u.startsWith("DO")) {
                if (s.length() == 2) {
                    return true;
                }
                if (s.length() > 2) {
                    char boundary = s.charAt(2);
                    if (Character.isWhitespace(boundary) || boundary == '$') {
                        return true;
                    }
                }
            }
            if (u.startsWith("DECLARE")) {
                return true;
            }
            if (u.startsWith("BEGIN")) {
                if (u.startsWith("BEGIN TRANSACTION")
                        || u.startsWith("BEGIN WORK")
                        || u.startsWith("BEGIN ISOLATION")) {
                    return false;
                }
                return true;
            }
            if (u.contains("CREATE ") && (u.contains(" FUNCTION")
                    || u.contains("\nFUNCTION")
                    || u.contains(" TRIGGER")
                    || u.contains("\nTRIGGER"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the statement is MySQL SQL/PSM procedural (native analysis via {@link MySqlPsmAnalyzer}).
     */
    private static boolean isMysqlProceduralContext(AstNode ast, String rawSql) {
        String t = ast.getNodeType();
        if (t != null) {
            switch (t) {
                case "BLOCK_STATEMENT",
                        "CREATE_PROCEDURE_STATEMENT",
                        "CREATE_FUNCTION_STATEMENT",
                        "CREATE_TRIGGER_STATEMENT",
                        "IF_STATEMENT",
                        "WHILE_STATEMENT",
                        "LOOP_STATEMENT",
                        "FOR_STATEMENT",
                        "CASE_STATEMENT",
                        "REPEAT_STATEMENT",
                        "OPEN_CURSOR_STATEMENT",
                        "FETCH_STATEMENT",
                        "CLOSE_CURSOR_STATEMENT",
                        "RAISE_STATEMENT",
                        "EXIT_WHEN_STATEMENT",
                        "SIGNAL_STATEMENT",
                        "RESIGNAL_STATEMENT",
                        "LEAVE_STATEMENT",
                        "ITERATE_STATEMENT",
                        "RETURN_STATEMENT" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        if (rawSql != null) {
            String s = rawSql.stripLeading();
            String u = s.toUpperCase(Locale.ROOT);
            if (u.startsWith("DECLARE")) {
                return true;
            }
            if (u.startsWith("BEGIN")) {
                if (u.startsWith("BEGIN TRANSACTION")
                        || u.startsWith("BEGIN WORK")
                        || u.startsWith("BEGIN ISOLATION")) {
                    return false;
                }
                return true;
            }
            if (u.contains("CREATE ") && (u.contains(" PROCEDURE")
                    || u.contains("\nPROCEDURE")
                    || u.contains(" FUNCTION")
                    || u.contains("\nFUNCTION")
                    || u.contains(" TRIGGER")
                    || u.contains("\nTRIGGER"))) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern LINE_COL_PAT =
            java.util.regex.Pattern.compile(
                    "\\bat line (\\d+)[,\\s]+column (\\d+)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Extrae {line, column} del mensaje del parser; devuelve null si no hay posición. */
    private static int[] parseLineCol(String msg) {
        if (msg == null || msg.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = LINE_COL_PAT.matcher(msg);
        return m.find()
                ? new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}
                : null;
    }

    private String friendlyErrorMessage(String raw, String query, Locale ui) {
        boolean es = isSpanishUi(ui);
        if (raw == null || raw.isBlank()) {
            return es ? "Error desconocido al analizar la query."
                    : "Unknown error while analyzing the query.";
        }
        String q = query == null ? "" : query.trim().toUpperCase(Locale.ROOT);

        if (q.startsWith("DELETE") && raw.contains("ASTERISK")) {
            return es
                    ? "Sintaxis inválida: DELETE no admite selección de columnas con *. "
                            + "La forma correcta es: DELETE FROM tabla [WHERE condición]"
                    : "Invalid syntax: DELETE does not support column selection with *. "
                            + "Correct form: DELETE FROM table [WHERE condition]";
        }
        if (q.startsWith("UPDATE") && raw.contains("SET")) {
            return es
                    ? "Sintaxis inválida en UPDATE: asegúrate de incluir la cláusula SET. "
                            + "Forma correcta: UPDATE tabla SET columna = valor [WHERE condición]"
                    : "Invalid UPDATE: include a SET clause. "
                            + "Correct form: UPDATE table SET column = value [WHERE condition]";
        }
        if (q.contains("LIKE") && (raw.contains("EOF") || raw.contains("value expression"))) {
            return "PARSE-INCOMPLETE-LIKE";
        }
        if (raw.contains("EOF")) {
            return "PARSE-INCOMPLETE";
        }
        if (q.contains(" IN ") && (raw.contains("value expression") || raw.contains("RIGHT_PAREN"))) {
            return "SE-EMPTY-IN";
        }
        if (es) {
            return raw
                    .replaceAll("\\s+at line \\d+, column \\d+", "")
                    .replaceAll("\\(ASTERISK\\)", "(*)")
                    .replaceAll("\\(IDENTIFIER\\)", "(nombre)")
                    .replaceAll("\\(KEYWORD\\)", "(palabra clave)")
                    .replaceAll("Expected KEYWORD '([^']+)'", "Se esperaba '$1'")
                    .replaceAll("but found '([^']+)'", "pero se encontró '$1'")
                    .trim();
        }
        return raw
                .replaceAll("\\s+at line \\d+, column \\d+", "")
                .replaceAll("\\(ASTERISK\\)", "(*)")
                .replaceAll("\\(IDENTIFIER\\)", "(identifier)")
                .replaceAll("\\(KEYWORD\\)", "(keyword)")
                .trim();
    }
}
