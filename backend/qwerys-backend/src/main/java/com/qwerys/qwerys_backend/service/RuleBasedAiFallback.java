package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.ai.AiFailureReason;
import com.qwerys.qwerys_backend.ai.AiLocaleHelper;
import com.qwerys.qwerys_backend.model.ai.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Offline fallback when no LLM API key is configured.
 */
@Component
public class RuleBasedAiFallback {

    private static final Pattern FROM_TABLE =
            Pattern.compile("(?i)\\bFROM\\s+([\\w.\"]+)");

    public String suggestQuery(SuggestQueryRequest req) {
        Locale locale = AiLocaleHelper.resolve(req.locale());
        String desc = req.description() != null ? req.description().trim().toLowerCase(Locale.ROOT) : "";
        if (desc.isEmpty()) {
            return msg(locale, "-- Describe what you need", "-- Describe lo que necesitas");
        }
        String db = safeDb(req.databaseType());
        if (isNoSqlFamily(db)) {
            return suggestNoSql(locale, db, desc, req);
        }
        String table = firstTable(req.schema());
        if (table == null) {
            table = "your_table";
        }
        if (desc.contains("insert") || desc.contains("crear") || desc.contains("añadir")) {
            return "INSERT INTO " + table + " (column1, column2) VALUES (?, ?);";
        }
        if (desc.contains("update") || desc.contains("actualizar")) {
            return "UPDATE " + table + " SET column1 = ? WHERE id = ?;";
        }
        if (desc.contains("delete") || desc.contains("eliminar")) {
            return "DELETE FROM " + table + " WHERE id = ?;";
        }
        List<String> cols = columnsOf(req.schema(), table);
        String colList = cols.isEmpty() ? "*" : String.join(", ", cols);
        return "SELECT " + colList + " FROM " + table + " WHERE /* add your condition */;";
    }

    public String autocomplete(AutocompleteRequest req) {
        String db = safeDb(req.databaseType());
        if (isNoSqlFamily(db)) {
            return autocompleteNoSql(req);
        }
        String partial = req.partialQuery() != null ? req.partialQuery() : "";
        String upper = partial.toUpperCase(Locale.ROOT);

        if (upper.trim().isEmpty() || (upper.matches("S.*") && upper.trim().length() <= 3)) {
            return "SELECT * FROM ";
        }
        if (upper.matches("(?s).*\\bFROM\\s*$")) {
            String table = firstTable(req.schema());
            return partial + (table != null ? table : "table_name");
        }
        if (upper.contains("FROM ") && upper.matches("(?s).*\\bWHERE\\s+$")) {
            var m = FROM_TABLE.matcher(partial);
            if (m.find()) {
                String table = m.group(1).replace("\"", "");
                List<String> cols = columnsOf(req.schema(), table);
                if (!cols.isEmpty()) {
                    return partial + cols.get(0);
                }
            }
            return partial + "column_name";
        }
        if (upper.matches("(?s).*\\bJOIN\\s+$")) {
            String table = firstTable(req.schema());
            return partial + (table != null ? table + " ON " + table + ".id = " : "related_table ON ");
        }
        return partial + " /* continue your query */";
    }

    public String generateQuery(GenerateQueryRequest req) {
        String db = safeDb(req.databaseType());
        if (isNoSqlFamily(db)) {
            return generateNoSql(req, db);
        }
        String op = req.operation() != null ? req.operation().trim().toUpperCase(Locale.ROOT) : "SELECT";
        String table = blankTo(req.tableName(), "table_name");
        List<String> cols = req.columns() != null ? req.columns() : List.of();
        boolean allCols = cols.isEmpty()
                || cols.stream().anyMatch(c -> "todas".equalsIgnoreCase(c) || "*".equals(c));
        String colList = allCols ? "*" : String.join(", ", cols);
        String where = req.condition() != null && !req.condition().isBlank()
                ? " WHERE " + req.condition().trim()
                : "";

        return switch (op) {
            case "INSERT" -> "INSERT INTO " + table + " (" + (allCols ? "col1, col2" : colList)
                    + ") VALUES (" + (allCols ? "?, ?" : placeholders(cols)) + ");";
            case "UPDATE" -> "UPDATE " + table + " SET "
                    + (allCols ? "col1 = ?" : setClause(cols)) + where + ";";
            case "DELETE" -> "DELETE FROM " + table + where + ";";
            default -> "SELECT " + colList + " FROM " + table + where + ";";
        };
    }

    public String explainErrors(ExplainErrorsRequest req) {
        Locale locale = AiLocaleHelper.resolve(req.locale());
        if (req.errors() == null || req.errors().isEmpty()) {
            return msg(locale,
                    "No structural errors to explain.",
                    "No hay errores estructurales que explicar.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(msg(locale, "Error summary for your query:\n\n", "Resumen de errores en tu consulta:\n\n"));
        int i = 1;
        for (AnalysisErrorDto e : req.errors()) {
            sb.append(i++).append(". [").append(e.code()).append("] ").append(e.message());
            if (e.suggestion() != null && !e.suggestion().isBlank()) {
                sb.append("\n   ").append(msg(locale, "Suggestion: ", "Sugerencia: ")).append(e.suggestion());
            }
            sb.append("\n");
        }
        sb.append("\n").append(msg(locale,
                "Review syntax for engine " + req.databaseType() + ".",
                "Revisa la sintaxis para el motor " + req.databaseType() + "."));
        return sb.toString();
    }

    /**
     * Convenience overload — delegates with {@link AiFailureReason#UNKNOWN} when
     * the caller does not have a classified reason (e.g. no AI key at all).
     */
    public ComplementAnalysisResponse complementAnalysisStructured(ComplementAnalysisRequest req, long ms) {
        return complementAnalysisStructured(req, ms, AiFailureReason.UNKNOWN);
    }

    public ComplementAnalysisResponse complementAnalysisStructured(
            ComplementAnalysisRequest req, long ms, AiFailureReason reason) {
        Locale locale = AiLocaleHelper.resolve(req.locale());
        String db = safeDb(req.databaseType());
        String query = req.query() != null ? req.query() : "";
        boolean nativeValid = Boolean.TRUE.equals(req.nativeIsValid());

        List<NativeFindingReviewDto> reviews = new ArrayList<>();
        List<AnalysisErrorDto> addErrors = new ArrayList<>();
        List<AnalysisWarningDto> addWarnings = new ArrayList<>();
        List<OptimizationDto> addOpts = new ArrayList<>();
        List<SyntaxCorrectionDto> syntax = new ArrayList<>();
        if (req.liveSchemaNote() != null && !req.liveSchemaNote().isBlank()) {
            addWarnings.add(new AnalysisWarningDto(
                    "AI-WARN-SCHEMA",
                    "WARNING",
                    req.liveSchemaNote().trim()));
        }

        detectTypoWhere(query, locale, addErrors, syntax);
        detectIncompletePaste(query, locale, req, addWarnings, syntax, reviews);
        ValidityCorrectionDto validityCorrection =
                detectPostgresAggregateFilterFalsePositive(query, locale, db, nativeValid, reviews);

        if (req.optimizations() != null) {
            for (OptimizationDto o : req.optimizations()) {
                if ("OPT-003".equals(o.ruleId())
                        && o.optimizedFragment() != null
                        && o.optimizedFragment().toUpperCase(Locale.ROOT).contains(" LIMIT ")
                        && ("oracle".equals(db) || "sqlserver".equals(db))) {
                    reviews.add(new NativeFindingReviewDto(
                            "OPT-003",
                            "PARTIAL",
                            msg(locale,
                                    "Native pagination hint may use wrong dialect; prefer FETCH FIRST (Oracle) or TOP (SQL Server).",
                                    "La paginación nativa puede usar dialecto incorrecto; prefiere FETCH FIRST (Oracle) o TOP (SQL Server).")));
                }
            }
        }

        if (nativeValid && query.toUpperCase(Locale.ROOT).contains("SELECT *")
                && !isNoSqlFamily(db)) {
            addOpts.add(new OptimizationDto(
                    "AI-OPT-STAR",
                    "MEDIUM",
                    msg(locale,
                            "SELECT * returns all columns; list only needed columns to reduce I/O and network cost.",
                            "SELECT * devuelve todas las columnas; lista solo las necesarias para reducir I/O y red."),
                    extractSelectStarLine(query),
                    suggestExplicitColumns(query, req)));
        }

        if ("dynamodb".equals(db) && nativeValid
                && query.toUpperCase(Locale.ROOT).contains("REMOVE")
                && query.toUpperCase(Locale.ROOT).contains("ADD")) {
            addWarnings.add(new AnalysisWarningDto("AI-WARN-DDB-MULTI", "WARNING"));
            addOpts.add(new OptimizationDto(
                    "AI-OPT-DDB-SPLIT",
                    "MEDIUM",
                    msg(locale,
                            "Combining SET, REMOVE, and ADD in one expression increases item size churn; consider splitting updates when possible.",
                            "Combinar SET, REMOVE y ADD en una expresión aumenta el tamaño del ítem; considera dividir actualizaciones."),
                    truncate(query, 120),
                    truncate(query, 80) + " /* split into focused UpdateItem calls */"));
        }

        String pedagogy = buildPedagogy(locale, req, addErrors, addOpts, reason);
        String optNotes = buildOptNotes(locale, req, reviews);

        return ComplementAnalysisSanitizer.sanitize(
                ComplementAnalysisResponse.ok(
                        pedagogy,
                        optNotes,
                        validityCorrection,
                        reviews,
                        addErrors,
                        addWarnings,
                        addOpts,
                        syntax,
                        null,
                        false,
                        "rule-based",
                        ms),
                req);
    }

    /**
     * PostgreSQL {@code agg(...) FILTER (WHERE ...)} is valid but the native SQL parser may not implement FILTER yet.
     */
    private static ValidityCorrectionDto detectPostgresAggregateFilterFalsePositive(
            String query,
            Locale locale,
            String db,
            boolean nativeValid,
            List<NativeFindingReviewDto> reviews) {
        if (nativeValid || query == null || query.isBlank()) {
            return null;
        }
        String dbNorm = db != null ? db.toLowerCase(Locale.ROOT) : "";
        if (!dbNorm.contains("postgres")) {
            return null;
        }
        if (!query.matches("(?is)FILTER\\s*\\(\\s*WHERE\\b")) {
            return null;
        }
        reviews.add(new NativeFindingReviewDto(
                "SYN-001-SQL",
                "DISAGREE",
                msg(locale,
                        "FILTER (WHERE ...) on aggregates is valid PostgreSQL; the native parser does not implement this clause yet.",
                        "FILTER (WHERE ...) en agregados es sintaxis válida en PostgreSQL; el parser nativo aún no implementa esta cláusula.")));
        return new ValidityCorrectionDto(
                true,
                true,
                msg(locale,
                        "The query is valid PostgreSQL; the syntax error is a false positive from parser limits (e.g. FILTER, LATERAL, CTE features).",
                        "La consulta es PostgreSQL válida; el error de sintaxis es un falso positivo por límites del parser (p. ej. FILTER, LATERAL, CTE)."));
    }

    private static void detectTypoWhere(
            String query,
            Locale locale,
            List<AnalysisErrorDto> addErrors,
            List<SyntaxCorrectionDto> syntax) {
        if (!query.matches("(?is).*\\bWHERF\\b.*")) {
            return;
        }
        String fixed = query.replaceAll("(?i)\\bWHERF\\b", "WHERE");
        addErrors.add(new AnalysisErrorDto(
                "AI-ERR-TYPO-WHERE",
                msg(locale, "Likely typo: WHERF should be WHERE.", "Posible typo: WHERF debería ser WHERE."),
                msg(locale, "Replace WHERF with WHERE.", "Reemplaza WHERF por WHERE.")));
        syntax.add(new SyntaxCorrectionDto(
                "SYN-001-SQL",
                fixed,
                msg(locale, "Corrected WHERE keyword.", "Palabra clave WHERE corregida.")));
    }

    private static void detectIncompletePaste(
            String query,
            Locale locale,
            ComplementAnalysisRequest req,
            List<AnalysisWarningDto> addWarnings,
            List<SyntaxCorrectionDto> syntax,
            List<NativeFindingReviewDto> reviews) {
        String trimmed = query.stripLeading();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        boolean startsWithClause = upper.startsWith("FROM ")
                || upper.startsWith("INNER JOIN")
                || upper.startsWith("LEFT JOIN")
                || upper.startsWith("WHERE ")
                || upper.startsWith("GROUP BY");
        if (!startsWithClause) {
            return;
        }
        addWarnings.add(new AnalysisWarningDto(
                "AI-WARN-INCOMPLETE",
                "WARNING",
                msg(locale,
                        "The query looks like a fragment (missing leading WITH/SELECT). Paste the full statement.",
                        "La consulta parece un fragmento (falta WITH/SELECT al inicio). Pega la sentencia completa.")));
        reviews.add(new NativeFindingReviewDto(
                "SYN-001-SQL",
                "PARTIAL",
                msg(locale,
                        "Query may be an incomplete paste (missing leading WITH/SELECT). Native syntax errors may be misleading.",
                        "La consulta puede ser un fragmento incompleto (falta WITH/SELECT inicial). Los errores de sintaxis nativos pueden confundir.")));
        if (req.errors() != null) {
            for (AnalysisErrorDto e : req.errors()) {
                if (e.code() != null && e.code().startsWith("SYN-")) {
                    syntax.add(new SyntaxCorrectionDto(
                            e.code(),
                            msg(locale,
                                    "(Paste the full query including WITH/SELECT at the top, then re-analyze.)",
                                    "(Pega la consulta completa incluyendo WITH/SELECT al inicio y vuelve a analizar.)"),
                            msg(locale,
                                    "Incomplete fragment detected.",
                                    "Fragmento incompleto detectado.")));
                }
            }
        }
    }

    private static String buildPedagogy(Locale locale, ComplementAnalysisRequest req,
            List<AnalysisErrorDto> addErrors, List<OptimizationDto> addOpts,
            AiFailureReason reason) {
        StringBuilder sb = new StringBuilder();
        sb.append(aiUnavailableMessage(locale, reason));
        sb.append(" ");
        if (req.errors() != null && !req.errors().isEmpty()) {
            sb.append(msg(locale,
                    "Review native errors and AI findings below. ",
                    "Revisa errores nativos y hallazgos IA abajo. "));
        }
        if (!addOpts.isEmpty()) {
            sb.append(msg(locale,
                    "Independent optimization suggestions are listed in the AI findings section.",
                    "Las sugerencias de optimización independientes están en la sección de hallazgos IA."));
        } else if (Boolean.TRUE.equals(req.nativeIsValid())) {
            sb.append(msg(locale,
                    "Validate with real data volumes and execution plans.",
                    "Valida con volúmenes reales y planes de ejecución."));
        }
        return sb.toString().trim();
    }

    /**
     * Returns a concise, honest sentence explaining why the AI second pass was
     * replaced by the rule-based mentor.  Messages are localised (es/en).
     */
    static String aiUnavailableMessage(Locale locale, AiFailureReason reason) {
        if (reason == null) {
            reason = AiFailureReason.UNKNOWN;
        }
        return switch (reason) {
            case RATE_LIMIT -> msg(locale,
                    "Daily AI token limit reached (Groq free tier). "
                    + "Wait ~1 hour or add an OpenRouter fallback key. Rule-based mentor applied.",
                    "Límite diario de tokens de IA alcanzado (Groq gratuito). "
                    + "Espera ~1 hora o añade una clave OpenRouter de respaldo. Se aplicó el mentor basado en reglas.");
            case INVALID_KEY -> msg(locale,
                    "AI key is invalid or expired. "
                    + "Update ai.api-key in application.properties. Rule-based mentor applied.",
                    "La clave de IA no es válida o expiró. "
                    + "Actualiza ai.api-key en application.properties. Se aplicó el mentor basado en reglas.");
            case NOT_CONFIGURED -> msg(locale,
                    "AI provider not configured (no API key set). "
                    + "Set ai.api-key in application.properties. Rule-based mentor applied.",
                    "Proveedor de IA no configurado (no hay clave API). "
                    + "Establece ai.api-key en application.properties. Se aplicó el mentor basado en reglas.");
            case TIMEOUT -> msg(locale,
                    "AI provider did not respond in time (timeout). Rule-based mentor applied.",
                    "El proveedor de IA no respondió a tiempo (timeout). Se aplicó el mentor basado en reglas.");
            case PARSE_ERROR -> msg(locale,
                    "AI responded but the output could not be parsed. Rule-based mentor applied.",
                    "La IA respondió pero su salida no pudo procesarse. Se aplicó el mentor basado en reglas.");
            default -> msg(locale,
                    "AI second pass unavailable. Rule-based mentor applied.",
                    "Segunda pasada IA no disponible. Se aplicó el mentor basado en reglas.");
        };
    }

    private static String buildOptNotes(
            Locale locale, ComplementAnalysisRequest req, List<NativeFindingReviewDto> reviews) {
        if (reviews.isEmpty() && (req.optimizations() == null || req.optimizations().isEmpty())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (NativeFindingReviewDto r : reviews) {
            sb.append("• ").append(r.referenceId()).append(": ").append(r.comment()).append('\n');
        }
        if (req.optimizations() != null) {
            sb.append(msg(locale,
                    "Confirm native snippets with EXPLAIN/plan for your workload.",
                    "Confirma fragmentos nativos con EXPLAIN/plan según tu carga."));
        }
        return sb.toString().trim();
    }

    private static String extractSelectStarLine(String query) {
        for (String line : query.split("\n")) {
            if (line.toUpperCase(Locale.ROOT).contains("SELECT")) {
                return line.trim();
            }
        }
        return "SELECT *";
    }

    private static String suggestExplicitColumns(String query, ComplementAnalysisRequest req) {
        var m = FROM_TABLE.matcher(query);
        if (m.find()) {
            String table = m.group(1).replace("\"", "");
            return "SELECT col1, col2, col3 FROM " + table;
        }
        return query.replaceFirst("(?i)SELECT\\s+\\*", "SELECT col1, col2, col3");
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    public String explainSecurityFinding(ExplainSecurityRequest req) {
        Locale locale = AiLocaleHelper.resolve(req.locale());
        if (AiLocaleHelper.isSpanish(locale)) {
            return """
                    Regla detectada: %s (%s)

                    %s

                    Pasos recomendados:
                    1. Reemplaza concatenación de strings por parámetros preparados o APIs seguras del motor.
                    2. Revisa permisos del usuario de base de datos (mínimo privilegio).
                    3. Vuelve a analizar la consulta corregida en el Analizador de QWERYS.
                    """.formatted(
                    nullToEmpty(req.ruleKey()),
                    nullToEmpty(req.patternId()),
                    nullToEmpty(req.riskSummary()));
        }
        return """
                Rule detected: %s (%s)

                %s

                Recommended steps:
                1. Replace string concatenation with prepared parameters or engine-safe APIs.
                2. Review database user permissions (least privilege).
                3. Re-analyze the corrected query in the QWERYS Analyzer.
                """.formatted(
                nullToEmpty(req.ruleKey()),
                nullToEmpty(req.patternId()),
                nullToEmpty(req.riskSummary()));
    }

    public String improveMigration(ImproveMigrationRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.currentMigration() != null ? req.currentMigration() : "");
        if (req.manualSteps() != null && !req.manualSteps().isEmpty()) {
            sb.append("\n\n// Pending steps (review manually):\n");
            for (String step : req.manualSteps()) {
                sb.append("// - ").append(step).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static boolean isNoSqlFamily(String db) {
        return switch (db) {
            case "mongodb", "redis", "cassandra", "dynamodb", "elasticsearch" -> true;
            default -> false;
        };
    }

    private static String suggestNoSql(Locale locale, String db, String desc, SuggestQueryRequest req) {
        String coll = firstTable(req.schema());
        if (coll == null) {
            coll = "collection";
        }
        return switch (db) {
            case "mongodb" -> "db." + coll + ".find({ /* filter */ })";
            case "redis" -> "GET key_name";
            case "cassandra" -> "SELECT * FROM " + coll + " WHERE id = ?;";
            case "dynamodb" -> "{ \"TableName\": \"" + coll + "\", \"Key\": { \"id\": { \"S\": \"value\" } } }";
            case "elasticsearch" -> "GET /" + coll + "/_search\n{ \"query\": { \"match_all\": {} } }";
            default -> msg(locale, "-- Configure Groq for better NoSQL suggestions", "-- Configure Groq para mejores sugerencias NoSQL");
        };
    }

    private static String autocompleteNoSql(AutocompleteRequest req) {
        String partial = req.partialQuery() != null ? req.partialQuery() : "";
        String db = safeDb(req.databaseType());
        if (partial.isBlank()) {
            return switch (db) {
                case "mongodb" -> "db.";
                case "redis" -> "GET ";
                case "elasticsearch" -> "GET /";
                default -> partial;
            };
        }
        return partial + " /* continue */";
    }

    private static String generateNoSql(GenerateQueryRequest req, String db) {
        String target = blankTo(req.tableName(), "collection");
        return switch (db) {
            case "mongodb" -> "db." + target + ".find({})";
            case "redis" -> "SET " + target + " value";
            case "cassandra" -> "SELECT * FROM " + target + " LIMIT 10;";
            case "dynamodb" -> "{ \"TableName\": \"" + target + "\" }";
            case "elasticsearch" -> "GET /" + target + "/_search";
            default -> "-- " + db;
        };
    }

    private static String safeDb(String db) {
        return db == null || db.isBlank() ? "mysql" : db.toLowerCase(Locale.ROOT);
    }

    private static String msg(Locale locale, String en, String es) {
        return AiLocaleHelper.isSpanish(locale) ? es : en;
    }

    private static String firstTable(List<TableInfo> schema) {
        if (schema == null || schema.isEmpty()) {
            return null;
        }
        return schema.get(0).name();
    }

    private static List<String> columnsOf(List<TableInfo> schema, String tableName) {
        if (schema == null) {
            return List.of();
        }
        return schema.stream()
                .filter(t -> t.name() != null && t.name().equalsIgnoreCase(tableName.replace("\"", "")))
                .findFirst()
                .map(TableInfo::columns)
                .orElse(List.of());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static String placeholders(List<String> cols) {
        return cols.stream().map(c -> "?").collect(Collectors.joining(", "));
    }

    private static String setClause(List<String> cols) {
        List<String> parts = new ArrayList<>();
        for (String c : cols) {
            parts.add(c.trim() + " = ?");
        }
        return String.join(", ", parts);
    }
}
