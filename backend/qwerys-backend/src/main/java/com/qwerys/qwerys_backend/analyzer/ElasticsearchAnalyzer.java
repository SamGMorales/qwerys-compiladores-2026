package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.analyzer.nosql.PainlessAnalyzer;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Elasticsearch Query DSL analyzer using Jackson to parse JSON and semantic rules over {@link JsonNode}.
 */
public final class ElasticsearchAnalyzer {

    private static final long ES_MAX_RESULT_WINDOW = 10_000L;

    /** Max nesting depth below {@code query} before REGLA 5 warns. */
    private static final int MAX_QUERY_NEST_DEPTH = 5;

    /** Known first-class ingest processor keys (Day 24L). */
    private static final Set<String> INGEST_PROCESSOR_KEYS = Set.of(
            "set", "remove", "rename", "convert", "grok", "date", "foreach", "script", "pipeline", "fail");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Locale ui = Locale.ENGLISH;

    private String t(String en, String es) {
        return AnalysisMessages.t(ui, en, es);
    }

    public ElasticsearchAnalyzer() {
    }

    public List<SemanticError> analyze(String query) {
        return analyze(query, Locale.ENGLISH);
    }

    public List<SemanticError> analyze(String query, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;

        String jsonPayload = extractJsonPayload(query);

        JsonNode root;
        try {
            root = MAPPER.readTree(jsonPayload != null ? jsonPayload : "");
        } catch (JsonProcessingException ex) {
            Integer line = null;
            Integer col = null;
            JsonLocation loc = ex.getLocation();
            if (loc != null) {
                long ln = loc.getLineNr();
                long cn = loc.getColumnNr();
                if (ln > 0) {
                    line = (int) ln;
                }
                if (cn > 0) {
                    col = (int) cn;
                }
            }
            out.add(new SemanticError(
                    "ES-SYNTAX-001",
                    t("Invalid JSON: " + safeMsg(ex), "JSON inválido: " + safeMsg(ex)),
                    t("Check JSON syntax at the indicated position.",
                            "Revise la sintaxis JSON en la posición indicada."),
                    SemanticError.Severity.ERROR,
                    line,
                    col));
            return out;
        }

        String restPrefix = extractRestPrefix(query);

        if (isStoredMustacheScriptPath(restPrefix, root)) {
            analyzeStoredMustacheSearchTemplate(root, out);
            return dedupeByCodePreserveOrder(out);
        }
        if (isRenderTemplateRestPath(restPrefix)) {
            analyzeRenderTemplateBody(root, out);
            return dedupeByCodePreserveOrder(out);
        }
        if (isSearchTemplateRestPath(restPrefix)) {
            analyzeSearchTemplateInvocationBody(root, out);
            return dedupeByCodePreserveOrder(out);
        }

        if (isIlmPolicyBody(root, restPrefix)) {
            analyzeIlmPolicy(root, out);
            return dedupeByCodePreserveOrder(out);
        }

        SnapshotPathKind snapPath = classifySnapshotPath(restPrefix);
        if (snapPath == SnapshotPathKind.RESTORE || isSnapshotRestoreBody(root, restPrefix)) {
            analyzeSnapshotRestore(root, out);
            return dedupeByCodePreserveOrder(out);
        }
        if (snapPath == SnapshotPathKind.REPOSITORY || isSnapshotRepositoryBody(root)) {
            analyzeSnapshotRepository(root, out);
            return dedupeByCodePreserveOrder(out);
        }
        if (snapPath == SnapshotPathKind.CREATE || isSnapshotCreateBody(root, restPrefix)) {
            analyzeSnapshotCreate(root, out);
            return dedupeByCodePreserveOrder(out);
        }

        if (isIngestPipelineBody(root)) {
            analyzeIngestPipeline(root, out);
            collectAndAnalyzePainlessScripts(root, out, false, false, false, null);
            return dedupeByCodePreserveOrder(out);
        }
        if (isReindexBody(root)) {
            analyzeReindexBody(root, out);
            collectAndAnalyzePainlessScripts(root, out, false, false, false, null);
            return dedupeByCodePreserveOrder(out);
        }

        validateNoAggregationTypesAsQueryRoot(root, out);

        analyzeSearchBodyMeta(root, out);

        collectAndAnalyzePainlessScripts(root, out, false, false, false, null);

        // REGLA 3 — size > 10000
        if (root.has("size")) {
            long sizeLong = jsonLong(root.get("size"), Long.MIN_VALUE);
            if (sizeLong != Long.MIN_VALUE && sizeLong > ES_MAX_RESULT_WINDOW) {
                out.add(new SemanticError(
                        "ES-SIZE-LIMIT",
                        t("Elasticsearch cannot return more than 10000 documents in a single request",
                                "Elasticsearch no puede devolver más de 10000 documentos en una sola petición"),
                        t("Use search_after or the scroll API for pagination beyond 10000 results",
                                "Use search_after o la API scroll para paginar más allá de 10000 resultados"),
                        SemanticError.Severity.ERROR));
            }
        }

        // REGLA 4 — from + size > 10000
        if (root.has("from") && root.has("size")) {
            Double fromN = coerceNumber(root.get("from"));
            Double sizeN = coerceNumber(root.get("size"));
            if (fromN != null && sizeN != null && Double.isFinite(fromN + sizeN)) {
                double sum = fromN + sizeN;
                if (sum > ES_MAX_RESULT_WINDOW) {
                    out.add(new SemanticError(
                            "ES-DEEP-PAGE",
                            t("Deep pagination (from + size > 10000) is expensive and disabled by default in ES",
                                    "Paginación profunda (from + size > 10000) es costosa y suele estar desactivada en ES"),
                            t("Use search_after for deep pagination — it is stateless and scales well",
                                    "Use search_after para paginación profunda: sin estado y escala bien"),
                            SemanticError.Severity.ERROR));
                }
            }
        }

        JsonNode queryNode = root.path("query");
        if (!queryNode.isMissingNode()) {
            // REGLA 1 — match_all only (+ no meaningful post_filter)
            if (isMatchAllOnlyEnvelope(queryNode) && !effectivePostFilterPresent(root)) {
                out.add(new SemanticError(
                        "ES-MATCH-ALL",
                        t("match_all without filters scans ALL documents — very expensive on large indices",
                                "match_all sin filtros recorre TODOS los documentos — muy costoso en índices grandes"),
                        t("Add a filter clause inside bool: {\"query\":{\"bool\":{\"filter\":[...]}}}",
                                "Añada un filtro dentro de bool: {\"query\":{\"bool\":{\"filter\":[...]}}}"),
                        SemanticError.Severity.WARNING));
            }

            // REGLA 5 — depth under query
            int depth = subtreeDepth(queryNode);
            if (depth > MAX_QUERY_NEST_DEPTH) {
                out.add(new SemanticError(
                        "ES-NESTED-DEPTH",
                        t("Query nesting depth exceeds 5 — deeply nested queries reduce performance",
                                "Profundidad de anidación > 5 — consultas muy anidadas reducen rendimiento"),
                        t("Flatten the query or use a bool query to combine conditions at the same level",
                                "Aplane la consulta o use bool para combinar condiciones al mismo nivel"),
                        SemanticError.Severity.WARNING));
            }

            // REGLA 8 — script inside query
            if (containsScriptKeyRecursive(queryNode)) {
                out.add(new SemanticError(
                        "ES-SCRIPT",
                        t("Script queries execute on every document — very slow and potentially unsafe",
                                "Consultas script evalúan cada documento — muy lentas y potencialmente inseguras"),
                        t("Index the computed value at write time and query the indexed field instead",
                                "Indexe el valor calculado al escribir y consulte el campo indexado"),
                        SemanticError.Severity.WARNING));
            }

            walkQueryClauses(queryNode, out);
        }

        analyzeKnnObject(root.path("knn"), out);

        // REGLA 2 — leading wildcard anywhere in body
        scanLeadingWildcardInTree(root, out);

        // REGLA 6 + extended — aggregations
        if (root.isObject()) {
            if (root.has("aggs")) {
                walkAggregationsBlock(root.get("aggs"), 1, false, out);
            }
            if (root.has("aggregations")) {
                walkAggregationsBlock(root.get("aggregations"), 1, false, out);
            }
            validateAggregationTreeMaxDepth(root, out);
        }

        // REGLA 7 — regexp anywhere (general perf warning)
        if (containsRegexpKeyAnywhere(root)) {
            out.add(new SemanticError(
                    "ES-REGEX",
                    t("Regex queries are CPU-intensive and can cause cluster instability on large indices",
                            "Consultas regex cargan mucho la CPU y pueden inestabilizar el clúster en índices grandes"),
                    t("Use a keyword field with a terms query, or edge n-grams for prefix matching",
                            "Use campo keyword con terms o edge n-grams para coincidencia por prefijo"),
                    SemanticError.Severity.WARNING));
        }

        return dedupeByCodePreserveOrder(out);
    }

    private void collectAndAnalyzePainlessScripts(JsonNode node, List<SemanticError> out,
            boolean inQuerySubtree, boolean inAggsSubtree, boolean requireReturn, String parentKey) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode val = e.getValue();
                boolean nextQuery = inQuerySubtree || "query".equals(key);
                boolean nextAggs = inAggsSubtree || "aggs".equals(key) || "aggregations".equals(key);
                boolean needReturnHere = requireReturn
                        || "script_score".equals(parentKey)
                        || "bucket_script".equals(parentKey)
                        || "bucket_selector".equals(parentKey);
                if ("script".equals(key)) {
                    analyzeOnePainlessScriptPayload(val, out, nextQuery, nextAggs, needReturnHere);
                }
                collectAndAnalyzePainlessScripts(val, out, nextQuery, nextAggs, requireReturn, key);
            }
        } else if (node.isArray()) {
            for (JsonNode el : node) {
                collectAndAnalyzePainlessScripts(el, out, inQuerySubtree, inAggsSubtree, requireReturn, parentKey);
            }
        }
    }

    private void analyzeOnePainlessScriptPayload(JsonNode scriptNode, List<SemanticError> out,
            boolean inQuerySubtree, boolean inAggsSubtree, boolean requireReturn) {
        String source = null;
        if (scriptNode != null && scriptNode.isTextual()) {
            source = normalizeInlinePainlessString(scriptNode.asText());
        } else if (scriptNode != null && scriptNode.isObject()) {
            if (!isPainlessLangNode(scriptNode)) {
                return;
            }
            JsonNode src = scriptNode.get("source");
            if (src != null && src.isTextual()) {
                source = src.asText();
            }
        }
        if (source == null || source.isBlank()) {
            return;
        }
        Set<String> schema = extractOptionalSchemaFields(scriptNode);
        var ctx = new PainlessAnalyzer.PainlessScriptContext(inQuerySubtree, inAggsSubtree, requireReturn, schema);
        out.addAll(PainlessAnalyzer.analyze(source, ctx, ui));
    }

    private static Set<String> extractOptionalSchemaFields(JsonNode scriptNode) {
        if (scriptNode == null || !scriptNode.isObject()) {
            return Set.of();
        }
        JsonNode hint = scriptNode.get("_qwerys_schema_fields");
        if (hint == null || !hint.isArray()) {
            return Set.of();
        }
        Set<String> s = new HashSet<>();
        for (JsonNode n : hint) {
            if (n != null && n.isTextual()) {
                String tx = n.asText().trim();
                if (!tx.isEmpty()) {
                    s.add(tx);
                }
            }
        }
        return s.isEmpty() ? Set.of() : Set.copyOf(s);
    }

    private static boolean isPainlessLangNode(JsonNode scriptObj) {
        JsonNode lang = scriptObj.get("lang");
        if (lang == null || lang.isNull()) {
            return true;
        }
        if (!lang.isTextual()) {
            return false;
        }
        String l = lang.asText().trim();
        if (l.isEmpty()) {
            return true;
        }
        if ("painless".equalsIgnoreCase(l)) {
            return true;
        }
        return false;
    }

    private static String normalizeInlinePainlessString(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.regionMatches(true, 0, "lang=painless", 0, "lang=painless".length())) {
            int cut = "lang=painless".length();
            while (cut < s.length() && Character.isWhitespace(s.charAt(cut))) {
                cut++;
            }
            if (cut < s.length() && s.charAt(cut) == '\n') {
                cut++;
            }
            String rest = s.substring(Math.min(cut, s.length())).strip();
            return rest.isEmpty() ? null : rest;
        }
        if (containsPainlessHeuristic(s)) {
            return s;
        }
        return null;
    }

    private static boolean containsPainlessHeuristic(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.contains("doc[")
                || lower.contains("ctx.")
                || lower.contains("params.")
                || lower.contains("def ")
                || lower.contains("return")
                || s.contains(";");
    }

    /**
     * Structural: aggregation-only clause keys must not appear as direct children of {@code query}.
     */
    private void validateNoAggregationTypesAsQueryRoot(JsonNode root, List<SemanticError> out) {
        if (root == null || !root.isObject()) {
            return;
        }
        JsonNode queryNode = root.path("query");
        if (queryNode.isMissingNode() || !queryNode.isObject()) {
            return;
        }
        Iterator<String> it = queryNode.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if (ElasticsearchLexer.isForbiddenAggregationKeyAtQueryRoot(key)) {
                out.add(new SemanticError(
                        "ES-STRUCT-001",
                        t("Aggregation type '" + key + "' cannot be used as a query type",
                                "El tipo de agregación '" + key + "' no puede usarse como tipo de consulta"),
                        t("Move '" + key + "' inside the aggs block: {\"aggs\":{\"name\":{\"" + key
                                + "\":{...}}}}",
                                "Mueva '" + key + "' al bloque aggs: {\"aggs\":{\"nombre\":{\"" + key
                                        + "\":{...}}}}"),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    /* -------------------- search / perf meta -------------------- */

    private void analyzeSearchBodyMeta(JsonNode root, List<SemanticError> out) {
        if (root == null || !root.isObject()) {
            return;
        }

        JsonNode tth = root.path("track_total_hits");
        if (tth.isBoolean() && !tth.asBoolean()) {
            out.add(new SemanticError(
                    "ES-TRACK-TOTAL",
                    t("track_total_hits: false skips exact document count — faster for large indices",
                            "track_total_hits: false omite el recuento exacto — más rápido en índices grandes"),
                    t("This is an optimization, not an error. Remove if you need the exact total count.",
                            "Es una optimización, no un error. Quítelo si necesita el total exacto."),
                    SemanticError.Severity.INFO));
        } else if (tth.isObject()) {
            JsonNode enabled = tth.path("enabled");
            if (enabled.isBoolean() && !enabled.asBoolean()) {
                out.add(new SemanticError(
                        "ES-TRACK-TOTAL",
                        t("track_total_hits: false skips exact document count — faster for large indices",
                                "track_total_hits: false omite el recuento exacto — más rápido en índices grandes"),
                        t("This is an optimization, not an error. Remove if you need the exact total count.",
                                "Es una optimización, no un error. Quítelo si necesita el total exacto."),
                        SemanticError.Severity.INFO));
            }
        }

        JsonNode src = root.path("_source");
        if (src.isBoolean() && src.asBoolean()) {
            out.add(new SemanticError(
                    "ES-SOURCE-FETCH",
                    t("Fetching full _source for large result sets is expensive",
                            "Obtener _source completo en conjuntos grandes es costoso"),
                    t("Use 'fields' to select only needed attributes instead of returning full _source",
                            "Use 'fields' para devolver solo los atributos necesarios en lugar de _source completo"),
                    SemanticError.Severity.WARNING));
        }

        JsonNode explain = root.path("explain");
        if (explain.isBoolean() && explain.asBoolean()) {
            out.add(new SemanticError(
                    "ES-EXPLAIN",
                    t("explain: true is for debugging only — adds significant overhead",
                            "explain: true es solo para depuración — añade mucha carga"),
                    t("Remove explain: true before using in production",
                            "Quite explain: true antes de usar en producción"),
                    SemanticError.Severity.WARNING));
        }

        JsonNode profile = root.path("profile");
        if (profile.isBoolean() && profile.asBoolean()) {
            out.add(new SemanticError(
                    "ES-PROFILE",
                    t("profile: true is for debugging only — adds significant overhead",
                            "profile: true es solo para depuración — añade mucha carga"),
                    t("Remove profile: true before using in production",
                            "Quite profile: true antes de usar en producción"),
                    SemanticError.Severity.WARNING));
        }
    }

    /* -------------------- query clause walk -------------------- */

    private void walkQueryClauses(JsonNode n, List<SemanticError> out) {
        if (n == null || n.isMissingNode()) {
            return;
        }
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> en = it.next();
                String k = en.getKey();
                JsonNode v = en.getValue();
                dispatchQueryKey(k, v, out);
                walkQueryClauses(v, out);
            }
        } else if (n.isArray()) {
            for (JsonNode el : n) {
                walkQueryClauses(el, out);
            }
        }
    }

    private void dispatchQueryKey(String k, JsonNode v, List<SemanticError> out) {
        switch (k) {
            case "range" -> validateRangeQuery(v, out);
            case "fuzzy" -> validateFuzzyQuery(v, out);
            case "regexp" -> validateRegexpQuery(v, out);
            case "wildcard" -> validateWildcardPatterns(v, out);
            case "more_like_this" -> validateMoreLikeThis(v, out);
            case "match_phrase", "match_phrase_prefix" -> validateMatchPhrase(v, out);
            case "function_score" -> validateFunctionScore(v, out);
            case "script_score" -> validateScriptScore(v, out);
            case "constant_score" -> validateConstantScore(v, out);
            case "geo_distance" -> validateGeoDistance(v, out);
            case "geo_bounding_box" -> validateGeoBoundingBox(v, out);
            case "geo_shape" -> validateGeoShape(v, out);
            case "knn" -> analyzeKnnObject(v, out);
            default -> {
            }
        }
    }

    private void validateRangeQuery(JsonNode rangeNode, List<SemanticError> out) {
        if (rangeNode == null || !rangeNode.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = rangeNode.fields();
        while (it.hasNext()) {
            JsonNode spec = it.next().getValue();
            if (spec == null || !spec.isObject()) {
                continue;
            }
            boolean dashDateLike = false;
            for (String bound : List.of("gt", "gte", "lt", "lte")) {
                JsonNode b = spec.get(bound);
                if (b != null && !b.isNull() && b.isTextual()) {
                    String t = b.asText();
                    if (t.contains("-")) {
                        dashDateLike = true;
                        break;
                    }
                }
            }
            if (!dashDateLike) {
                continue;
            }
            if (!spec.has("format")) {
                out.add(new SemanticError(
                        "ES-DATE-FORMAT",
                        t("range query on dates without 'format' may cause ambiguous date parsing",
                                "range sobre fechas sin 'format' puede dar fechas ambiguas"),
                        t("Specify 'format' in the range query to avoid timezone and format ambiguity",
                                "Especifique 'format' en el range para evitar ambigüedad de zona y formato"),
                        SemanticError.Severity.INFO));
            }
            if (!spec.has("time_zone")) {
                out.add(new SemanticError(
                        "ES-DATE-TZ",
                        t("Date range without time_zone may return unexpected results for multi-timezone data",
                                "Rango de fechas sin time_zone puede dar resultados inesperados con varias zonas"),
                        t("Add time_zone: '+00:00' or your target timezone to the range query",
                                "Añada time_zone: '+00:00' o su zona objetivo al range"),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private void validateFuzzyQuery(JsonNode fuzzyNode, List<SemanticError> out) {
        if (fuzzyNode == null || !fuzzyNode.isObject()) {
            return;
        }
        Iterator<JsonNode> it = fuzzyNode.elements();
        while (it.hasNext()) {
            JsonNode inner = it.next();
            if (!inner.isObject()) {
                continue;
            }
            JsonNode fz = inner.get("fuzziness");
            if (fz == null || fz.isNull()) {
                continue;
            }
            if (fz.isIntegralNumber() && fz.intValue() > 2) {
                fuzzinessHighWarn(out);
            } else if (fz.isTextual()) {
                String s = fz.asText().trim();
                try {
                    int n = Integer.parseInt(s);
                    if (n > 2) {
                        fuzzinessHighWarn(out);
                    }
                } catch (NumberFormatException ignored) {
                    if (s.matches("\\d+")) {
                        try {
                            if (Integer.parseInt(s) > 2) {
                                fuzzinessHighWarn(out);
                            }
                        } catch (NumberFormatException ignored2) {
                        }
                    }
                }
            }
        }
    }

    private void fuzzinessHighWarn(List<SemanticError> out) {
        out.add(new SemanticError(
                "ES-FUZZY-HIGH",
                t("fuzziness > 2 rarely improves recall but significantly impacts performance",
                        "fuzziness > 2 raramente mejora el recall pero impacta mucho el rendimiento"),
                t("Use fuzziness: 1 or 2; consider AUTO for field-length-aware fuzziness",
                        "Use fuzziness: 1 o 2; considere AUTO según longitud del campo"),
                SemanticError.Severity.WARNING));
    }

    private void validateRegexpQuery(JsonNode regNode, List<SemanticError> out) {
        if (regNode == null || !regNode.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = regNode.fields();
        while (it.hasNext()) {
            JsonNode spec = it.next().getValue();
            if (spec == null || !spec.isObject()) {
                continue;
            }
            if (!spec.has("max_determinized_states")) {
                out.add(new SemanticError(
                        "ES-REGEXP-STATES",
                        t("Complex regex without max_determinized_states limit may cause out-of-memory errors",
                                "Regex compleja sin max_determinized_states puede causar errores de memoria"),
                        t("Add max_determinized_states: 10000 to limit automaton complexity",
                                "Añada max_determinized_states: 10000 para limitar la complejidad del autómata"),
                        SemanticError.Severity.WARNING));
                return;
            }
        }
    }

    private void validateWildcardPatterns(JsonNode wildcardNode, List<SemanticError> out) {
        if (wildcardNode == null || !wildcardNode.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = wildcardNode.fields();
        while (it.hasNext()) {
            JsonNode v = it.next().getValue();
            if (v.isTextual() && hasLeadingWildcard(v.asText())) {
                emitWildcardLead(out);
            } else if (v.isObject()) {
                JsonNode val = v.get("value");
                if (val != null && val.isTextual() && hasLeadingWildcard(val.asText())) {
                    emitWildcardLead(out);
                }
            }
        }
    }

    private void validateMoreLikeThis(JsonNode mlt, List<SemanticError> out) {
        if (mlt == null || !mlt.isObject()) {
            return;
        }
        if (!mlt.has("min_term_freq")) {
            out.add(new SemanticError(
                    "ES-MLT-FREQ",
                    t("more_like_this without min_term_freq includes very rare terms",
                            "more_like_this sin min_term_freq incluye términos muy raros"),
                    t("Set min_term_freq: 2 to filter out terms that appear only once",
                            "Use min_term_freq: 2 para filtrar términos que aparecen una sola vez"),
                    SemanticError.Severity.INFO));
        }
    }

    private void validateMatchPhrase(JsonNode mp, List<SemanticError> out) {
        if (mp == null || !mp.isObject()) {
            return;
        }
        Iterator<JsonNode> it = mp.elements();
        while (it.hasNext()) {
            JsonNode inner = it.next();
            if (!inner.isObject()) {
                continue;
            }
            JsonNode slop = inner.get("slop");
            if (slop != null && slop.isIntegralNumber() && slop.intValue() > 5) {
                out.add(new SemanticError(
                        "ES-PHRASE-SLOP",
                        t("High slop in match_phrase reduces precision significantly",
                                "slop alto en match_phrase reduce mucho la precisión"),
                        t("Keep slop <= 2 for reasonable precision; use match for looser matching",
                                "Mantenga slop <= 2; use match para coincidencia más flexible"),
                        SemanticError.Severity.WARNING));
                return;
            }
        }
    }

    private void validateFunctionScore(JsonNode fs, List<SemanticError> out) {
        if (fs == null || !fs.isObject()) {
            return;
        }
        if (!fs.has("query")) {
            out.add(new SemanticError(
                    "ES-FUNC-SCORE-QUERY",
                    t("function_score without query matches ALL documents before scoring",
                            "function_score sin query coincide con TODOS los documentos antes de puntuar"),
                    t("Add a query inside function_score to filter documents before applying functions",
                            "Añada query dentro de function_score para filtrar antes de las funciones"),
                    SemanticError.Severity.WARNING));
        }
        JsonNode bm = fs.get("boost_mode");
        if (bm != null && bm.isTextual() && "replace".equalsIgnoreCase(bm.asText())) {
            out.add(new SemanticError(
                    "ES-FUNC-SCORE-REPLACE",
                    t("boost_mode 'replace' ignores the original query relevance score entirely",
                            "boost_mode 'replace' ignora por completo la relevancia original de la consulta"),
                    t("Use boost_mode: 'multiply' or 'sum' to combine original score with function score",
                            "Use boost_mode: 'multiply' o 'sum' para combinar con la puntuación original"),
                    SemanticError.Severity.INFO));
        }
    }

    private void validateScriptScore(JsonNode ss, List<SemanticError> out) {
        if (ss == null || !ss.isObject()) {
            return;
        }
        JsonNode script = ss.get("script");
        if (script == null || script.isNull()) {
            return;
        }
        if (script.isObject() && !script.has("lang")) {
            out.add(new SemanticError(
                    "ES-SCRIPT-SCORE-LANG",
                    t("script_score without lang defaults to 'painless'",
                            "script_score sin lang usa por defecto 'painless'"),
                    t("Add lang: 'painless' explicitly to make intent clear",
                            "Añada lang: 'painless' explícitamente para dejar clara la intención"),
                    SemanticError.Severity.INFO));
        }
    }

    private void validateConstantScore(JsonNode cs, List<SemanticError> out) {
        if (cs == null || !cs.isObject()) {
            return;
        }
        if (!cs.has("boost")) {
            out.add(new SemanticError(
                    "ES-CONST-SCORE-BOOST",
                    t("constant_score without boost defaults to 1.0",
                            "constant_score sin boost usa 1.0 por defecto"),
                    t("Add boost: 1.5 (or desired value) to make the score explicit",
                            "Añada boost: 1.5 (u otro valor) para hacer explícita la puntuación"),
                    SemanticError.Severity.INFO));
        }
    }

    private void validateGeoDistance(JsonNode gd, List<SemanticError> out) {
        if (gd == null || !gd.isObject()) {
            return;
        }
        if (!gd.has("unit")) {
            out.add(new SemanticError(
                    "ES-GEO-DIST-UNIT",
                    t("geo_distance without unit defaults to meters",
                            "geo_distance sin unit usa metros por defecto"),
                    t("Add unit: 'km', 'mi', 'm' etc. to make the intent explicit",
                            "Añada unit: 'km', 'mi', 'm', etc. para explicitar la unidad"),
                    SemanticError.Severity.INFO));
        }
    }

    private void validateGeoBoundingBox(JsonNode gbb, List<SemanticError> out) {
        if (gbb == null || !gbb.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = gbb.fields();
        while (it.hasNext()) {
            JsonNode box = it.next().getValue();
            if (box == null || !box.isObject()) {
                continue;
            }
            JsonNode tl = box.get("top_left");
            JsonNode br = box.get("bottom_right");
            if (tl == null || br == null) {
                continue;
            }
            Double topLat = latFromGeoPoint(tl);
            Double bottomLat = latFromGeoPoint(br);
            if (topLat != null && bottomLat != null && topLat < bottomLat) {
                out.add(new SemanticError(
                        "ES-GEO-BBOX-LAT",
                        t("geo_bounding_box: top_left latitude must be greater than bottom_right latitude",
                                "geo_bounding_box: la latitud de top_left debe ser mayor que la de bottom_right"),
                        t("Swap top_left and bottom_right coordinates or correct the latitude values",
                                "Intercambie top_left y bottom_right o corrija los valores de latitud"),
                        SemanticError.Severity.ERROR));
                return;
            }
        }
    }

    private static Double latFromGeoPoint(JsonNode node) {
        if (node != null && node.isObject()) {
            if (node.has("lat")) {
                return coerceNumber(node.get("lat"));
            }
        }
        return null;
    }

    private void validateGeoShape(JsonNode gs, List<SemanticError> out) {
        if (gs == null || !gs.isObject()) {
            return;
        }
        Iterator<JsonNode> it = gs.elements();
        while (it.hasNext()) {
            JsonNode inner = it.next();
            if (inner != null && inner.isObject() && !inner.has("relation")) {
                out.add(new SemanticError(
                        "ES-GEO-SHAPE-REL",
                        t("geo_shape without relation defaults to 'intersects'",
                                "geo_shape sin relation usa por defecto 'intersects'"),
                        t("Add relation: 'intersects', 'within', or 'contains' to make intent explicit",
                                "Añada relation: 'intersects', 'within' o 'contains' para explicitar"),
                        SemanticError.Severity.INFO));
                return;
            }
        }
    }

    private void analyzeKnnObject(JsonNode knn, List<SemanticError> out) {
        if (knn == null || knn.isNull() || knn.isMissingNode()) {
            return;
        }
        if (knn.isArray()) {
            for (JsonNode el : knn) {
                analyzeSingleKnn(el, out);
            }
            return;
        }
        if (knn.isObject()) {
            if (knn.has("field")) {
                analyzeSingleKnn(knn, out);
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> it = knn.fields();
            while (it.hasNext()) {
                analyzeSingleKnn(it.next().getValue(), out);
            }
        }
    }

    private void analyzeSingleKnn(JsonNode spec, List<SemanticError> out) {
        if (spec == null || !spec.isObject()) {
            return;
        }
        if (!spec.has("k")) {
            out.add(new SemanticError(
                    "ES-KNN-K",
                    t("knn query requires 'k' (number of nearest neighbors to return)",
                            "La consulta knn requiere 'k' (vecinos más cercanos a devolver)"),
                    t("Add k: 10 (or your desired number) to the knn query",
                            "Añada k: 10 (u otro número) a la consulta knn"),
                    SemanticError.Severity.ERROR));
        }
        if (!spec.has("num_candidates")) {
            out.add(new SemanticError(
                    "ES-KNN-CANDIDATES",
                    t("knn without num_candidates uses default; low values reduce recall",
                            "knn sin num_candidates usa el valor por defecto; valores bajos reducen el recall"),
                    t("Set num_candidates to at least 1.5 * k for good accuracy (e.g. k=10 → num_candidates=15)",
                            "Use num_candidates al menos 1.5 × k (p.ej. k=10 → num_candidates=15)"),
                    SemanticError.Severity.WARNING));
        }
    }

    /* -------------------- aggregations -------------------- */

    private void walkAggregationsBlock(JsonNode aggsBranch, int depth, boolean insideNested,
            List<SemanticError> out) {
        if (aggsBranch == null || !aggsBranch.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = aggsBranch.fields();
        while (it.hasNext()) {
            JsonNode body = it.next().getValue();
            inspectAggBody(body, depth, insideNested, out);
        }
    }

    private void inspectAggBody(JsonNode body, int depth, boolean insideNested,
            List<SemanticError> out) {
        if (body == null || !body.isObject()) {
            return;
        }
        boolean isNestedAgg = body.has("nested") && body.get("nested").isObject();
        if (isNestedAgg) {
            JsonNode nestedSpec = body.get("nested");
            if (!nestedSpec.has("path") || !nestedSpec.get("path").isTextual()
                    || nestedSpec.get("path").asText().isBlank()) {
                out.add(new SemanticError(
                        "ES-NESTED-NO-PATH",
                        t("nested aggregation requires 'path' pointing to a nested field",
                                "La agregación nested requiere 'path' al campo anidado"),
                        t("Add path: 'field_name' where field_name is the nested object field",
                                "Añada path: 'nombre_campo' donde nombre_campo es el objeto nested"),
                        SemanticError.Severity.ERROR));
            }
        }
        if (body.has("reverse_nested") && !insideNested) {
            out.add(new SemanticError(
                    "ES-REV-NESTED-CONTEXT",
                    t("reverse_nested must be used inside a nested aggregation",
                            "reverse_nested debe usarse dentro de una agregación nested"),
                    t("Place reverse_nested as a sub-aggregation of a nested aggregation",
                            "Coloque reverse_nested como sub-agregación de nested"),
                    SemanticError.Severity.ERROR));
        }

        Iterator<Map.Entry<String, JsonNode>> fields = body.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String key = e.getKey();
            JsonNode spec = e.getValue();
            if ("aggs".equals(key) || "meta".equals(key)) {
                continue;
            }
            applyAggregationRule(key, spec, out);
        }

        JsonNode childAggs = body.get("aggs");
        if (childAggs != null && childAggs.isObject()) {
            boolean nextInside = insideNested || isNestedAgg;
            walkAggregationsBlock(childAggs, depth + 1, nextInside, out);
        }
    }

    private void applyAggregationRule(String key, JsonNode spec, List<SemanticError> out) {
        switch (key) {
            case "date_histogram" -> validateDateHistogramAgg(spec, out);
            case "terms" -> validateTermsAggDetails(spec, out);
            case "cardinality" -> {
                if (spec != null && spec.isObject()) {
                    JsonNode p = spec.get("precision_threshold");
                    long pt = jsonLong(p, 0L);
                    if (pt > 40_000L) {
                        out.add(new SemanticError(
                                "ES-CARDINALITY-THRESHOLD",
                                t("precision_threshold above 40000 uses excessive memory with minimal accuracy gain",
                                        "precision_threshold por encima de 40000 usa mucha memoria con poca ganancia"),
                                t("Set precision_threshold between 1000 and 40000 for the best memory/accuracy balance",
                                        "Use precision_threshold entre 1000 y 40000 para equilibrar memoria y precisión"),
                                SemanticError.Severity.WARNING));
                    }
                }
            }
            case "bucket_script" -> {
                if (spec == null || !spec.isObject() || !spec.has("buckets_path")) {
                    out.add(new SemanticError(
                            "ES-BUCKET-SCRIPT-PATH",
                            t("bucket_script requires buckets_path mapping to reference sibling aggregations",
                                    "bucket_script requiere buckets_path que referencie agregaciones hermanas"),
                            t("Add buckets_path: { var1: 'agg_name', var2: 'agg_name2' } to bucket_script",
                                    "Añada buckets_path: { var1: 'agg', var2: 'agg2' } a bucket_script"),
                            SemanticError.Severity.ERROR));
                }
            }
            case "bucket_selector" -> {
                if (spec == null || !spec.isObject()
                        || !spec.has("buckets_path")
                        || !spec.has("script")) {
                    out.add(new SemanticError(
                            "ES-BUCKET-SEL-FIELDS",
                            t("bucket_selector requires both buckets_path and script",
                                    "bucket_selector requiere buckets_path y script"),
                            t("Add buckets_path (references) and script (condition) to bucket_selector",
                                    "Añada buckets_path (referencias) y script (condición) a bucket_selector"),
                            SemanticError.Severity.ERROR));
                }
            }
            case "moving_avg" -> out.add(new SemanticError(
                    "ES-MOVING-AVG-DEPRECATED",
                    t("moving_avg is deprecated; use moving_fn instead",
                            "moving_avg está obsoleto; use moving_fn"),
                    t("Replace moving_avg with moving_fn using MovingFunctions.unweightedAvg(values)",
                            "Reemplace moving_avg por moving_fn con MovingFunctions.unweightedAvg(values)"),
                    SemanticError.Severity.WARNING));
            default -> {
            }
        }
    }

    private void validateAggregationTreeMaxDepth(JsonNode root, List<SemanticError> out) {
        int d = 0;
        if (root.has("aggs")) {
            d = Math.max(d, maxAggsNestingDepth(root.get("aggs")));
        }
        if (root.has("aggregations")) {
            d = Math.max(d, maxAggsNestingDepth(root.get("aggregations")));
        }
        if (d > 4) {
            out.add(new SemanticError(
                    "ES-AGG-DEPTH",
                    t("Deeply nested aggregations (depth > 4) impact memory and performance",
                            "Agregaciones muy anidadas (profundidad > 4) impactan memoria y rendimiento"),
                    t("Restructure aggregations to reduce nesting or use composite aggregation",
                            "Reestructure agregaciones o use composite aggregation"),
                    SemanticError.Severity.WARNING));
        }
    }

    /** Deepest chain of {@code aggs} objects under a branch (1 = direct sub-aggregations only). */
    private static int maxAggsNestingDepth(JsonNode aggsLevel) {
        if (aggsLevel == null || !aggsLevel.isObject()) {
            return 0;
        }
        int deepest = 0;
        Iterator<JsonNode> it = aggsLevel.elements();
        while (it.hasNext()) {
            JsonNode body = it.next();
            if (body != null && body.isObject()) {
                JsonNode inner = body.get("aggs");
                if (inner != null && inner.isObject()) {
                    deepest = Math.max(deepest, 1 + maxAggsNestingDepth(inner));
                }
            }
        }
        return deepest;
    }

    private void validateDateHistogramAgg(JsonNode spec, List<SemanticError> out) {
        if (spec == null || !spec.isObject()) {
            return;
        }
        boolean hasLegacyInterval = spec.has("interval");
        if (hasLegacyInterval) {
            out.add(new SemanticError(
                    "ES-DATE-HIST-DEPRECATED",
                    t("interval parameter is deprecated since ES 7.2+",
                            "El parámetro interval está obsoleto desde ES 7.2+"),
                    t("Replace 'interval' with 'calendar_interval' or 'fixed_interval'",
                            "Reemplace 'interval' por 'calendar_interval' o 'fixed_interval'"),
                    SemanticError.Severity.WARNING));
        }
        boolean hasCal = spec.has("calendar_interval");
        boolean hasFix = spec.has("fixed_interval");
        if (!hasCal && !hasFix) {
            out.add(new SemanticError(
                    "ES-DATE-HIST-INTERVAL",
                    t("date_histogram requires either calendar_interval or fixed_interval",
                            "date_histogram requiere calendar_interval o fixed_interval"),
                    t("Add calendar_interval (e.g. '1d', '1M') or fixed_interval (e.g. '7d', '1h')",
                            "Añada calendar_interval (p.ej. '1d', '1M') o fixed_interval (p.ej. '7d', '1h')"),
                    SemanticError.Severity.ERROR));
        }
    }

    private void validateTermsAggDetails(JsonNode terms, List<SemanticError> out) {
        if (terms == null || !terms.isObject()) {
            return;
        }
        if (!terms.has("size")) {
            out.add(new SemanticError(
                    "ES-TERMS-NO-SIZE",
                    t("terms aggregation without explicit size returns top 10 by default",
                            "terms sin size explícito devuelve 10 buckets por defecto"),
                    t("Set size explicitly to control how many buckets are returned",
                            "Defina size explícitamente para controlar cuántos buckets se devuelven"),
                    SemanticError.Severity.INFO));
        }
    }

    /* -------------------- shared helpers -------------------- */

    private static long jsonLong(JsonNode n, long dflt) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return dflt;
        }
        if (n.isIntegralNumber()) {
            return n.longValue();
        }
        if (n.isFloatingPointNumber()) {
            return (long) n.doubleValue();
        }
        if (n.isTextual()) {
            try {
                double d = Double.parseDouble(n.asText());
                if (Double.isFinite(d)) {
                    return (long) d;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return dflt;
    }

    private static String safeMsg(JsonProcessingException ex) {
        String m = ex.getOriginalMessage();
        if (m != null && !m.isBlank()) {
            return m;
        }
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private static boolean effectivePostFilterPresent(JsonNode root) {
        JsonNode pf = root.get("post_filter");
        if (pf == null || pf.isNull() || pf.isMissingNode()) {
            return false;
        }
        if (pf.isObject()) {
            return pf.size() > 0;
        }
        return pf.isArray() ? pf.size() > 0 : true;
    }

    private static boolean isMatchAllOnlyEnvelope(JsonNode queryNode) {
        if (queryNode == null || !queryNode.isObject()) {
            return false;
        }
        if (queryNode.size() != 1) {
            return false;
        }
        Iterator<String> fn = queryNode.fieldNames();
        String only = fn.hasNext() ? fn.next() : null;
        if (!"match_all".equals(only)) {
            return false;
        }
        JsonNode inner = queryNode.get("match_all");
        return inner != null && inner.isObject();
    }

    private static int subtreeDepth(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return 0;
        }
        if (n.isObject()) {
            int max = 0;
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                JsonNode child = it.next().getValue();
                max = Math.max(max, subtreeDepth(child));
            }
            return 1 + max;
        }
        if (n.isArray()) {
            int max = 0;
            for (JsonNode e : n) {
                max = Math.max(max, subtreeDepth(e));
            }
            return 1 + max;
        }
        return 0;
    }

    private static Double coerceNumber(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return null;
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        if (n.isTextual()) {
            try {
                return Double.parseDouble(n.asText());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean containsScriptKeyRecursive(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return false;
        }
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> en = it.next();
                if ("script".equals(en.getKey())) {
                    return true;
                }
                if (containsScriptKeyRecursive(en.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (n.isArray()) {
            for (JsonNode el : n) {
                if (containsScriptKeyRecursive(el)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void scanLeadingWildcardInTree(JsonNode n, List<SemanticError> out) {
        if (n == null || n.isMissingNode()) {
            return;
        }
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> en = it.next();
                JsonNode fieldNode = en.getValue();
                if ("wildcard".equals(en.getKey()) && fieldNode != null && fieldNode.isObject()) {
                    validateWildcardPatterns(fieldNode, out);
                } else if ("query_string".equals(en.getKey()) && fieldNode != null && fieldNode.isObject()) {
                    JsonNode q = fieldNode.get("query");
                    if (q != null && q.isTextual() && hasLeadingWildcard(q.asText())) {
                        emitWildcardLead(out);
                    }
                }
                scanLeadingWildcardInTree(fieldNode, out);
            }
        } else if (n.isArray()) {
            for (JsonNode el : n) {
                scanLeadingWildcardInTree(el, out);
            }
        }
    }

    private static boolean hasLeadingWildcard(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        char c = s.charAt(0);
        return c == '*' || c == '?';
    }

    private void emitWildcardLead(List<SemanticError> out) {
        for (SemanticError s : out) {
            if ("ES-WILDCARD-LEAD".equals(s.code())) {
                return;
            }
        }
        out.add(new SemanticError(
                "ES-WILDCARD-LEAD",
                t("Leading wildcard is very slow; consider using edge n-grams instead",
                        "Comodín inicial es muy lento; considere edge n-grams"),
                t("Use edge n-grams in the mapping instead of leading wildcards",
                        "Use edge n-grams en el mapping en lugar de comodines iniciales"),
                SemanticError.Severity.WARNING));
    }

    private static boolean containsRegexpKeyAnywhere(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return false;
        }
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> en = it.next();
                if ("regexp".equals(en.getKey())) {
                    return true;
                }
                if (containsRegexpKeyAnywhere(en.getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (n.isArray()) {
            for (JsonNode el : n) {
                if (containsRegexpKeyAnywhere(el)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<SemanticError> dedupeByCodePreserveOrder(List<SemanticError> in) {
        List<SemanticError> res = new ArrayList<>(in.size());
        List<String> seen = new ArrayList<>();
        outer:
        for (SemanticError se : in) {
            String c = se.code();
            if ("ES-STRUCT-001".equals(c)) {
                res.add(se);
                continue;
            }
            for (String prev : seen) {
                if (prev.equals(c)) {
                    continue outer;
                }
            }
            seen.add(c);
            res.add(se);
        }
        return res;
    }

    /**
     * Strips optional REST verb/path prefix so {@code PUT _ingest/pipeline/x { ... }} and
     * {@code POST _reindex\n{ ... }} still parse as JSON.
     */
    private static String extractJsonPayload(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        if (s.isEmpty()) {
            return s;
        }
        int brace = s.indexOf('{');
        if (brace <= 0) {
            return s;
        }
        // Leading text is typically: PUT _ingest/pipeline/name  or  POST _reindex
        return s.substring(brace);
    }

    private static boolean isIngestPipelineBody(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        JsonNode procs = root.get("processors");
        return procs != null && procs.isArray();
    }

    private static boolean isReindexBody(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        JsonNode src = root.get("source");
        JsonNode dst = root.get("dest");
        return src != null && src.isObject() && dst != null && dst.isObject();
    }

    private void analyzeIngestPipeline(JsonNode root, List<SemanticError> out) {
        JsonNode onFailure = root.get("on_failure");
        boolean pipelineOnFailure = onFailure != null && onFailure.isArray() && onFailure.size() > 0;
        boolean anyStepOnFailure = false;
        JsonNode processors = root.get("processors");
        for (int i = 0; i < processors.size(); i++) {
            JsonNode step = processors.get(i);
            if (step != null && step.isObject()) {
                JsonNode stepOf = step.get("on_failure");
                if (stepOf != null && stepOf.isArray() && stepOf.size() > 0) {
                    anyStepOnFailure = true;
                    break;
                }
            }
        }
        if (!pipelineOnFailure && !anyStepOnFailure) {
            out.add(new SemanticError(
                    "ES-IP-001",
                    t("Ingest pipeline without on_failure may drop or hide processor errors silently",
                            "Errores silenciosos: pipeline sin on_failure"),
                    t("Add a top-level on_failure handler or per-processor on_failure to surface failures",
                            "Añada on_failure a nivel de pipeline o por processor para exponer fallos"),
                    SemanticError.Severity.WARNING));
        }

        int n = processors.size();
        if (n > 20) {
            out.add(new SemanticError(
                    "ES-IP-002",
                    t("Ingest pipeline has more than 20 processors — harder to maintain and debug",
                            "Considera dividir: pipeline con más de 20 processors"),
                    t("Split into smaller pipelines and chain with the pipeline processor",
                            "Divida en pipelines más pequeños y encadene con el processor pipeline"),
                    SemanticError.Severity.WARNING));
        }

        Set<String> removedFields = new HashSet<>();
        for (int i = 0; i < n; i++) {
            JsonNode step = processors.get(i);
            if (step == null || !step.isObject()) {
                continue;
            }
            String procType = firstIngestProcessorType(step);
            if (procType == null) {
                continue;
            }
            JsonNode body = step.get(procType);
            if (body == null || !body.isObject()) {
                continue;
            }

            switch (procType) {
                case "pipeline" -> out.add(new SemanticError(
                        "ES-IP-003",
                        t("Nested pipeline processor — referenced pipeline existence is not verified statically",
                                "Processor 'pipeline' (anidado) sin verificar existencia"),
                        t("Confirm the referenced pipeline is registered before using it in production",
                            "Verifique que el pipeline referenciado exista en el clúster"),
                        SemanticError.Severity.INFO));
                case "foreach" -> {
                    JsonNode innerProc = body.get("processor");
                    if (innerProc == null || innerProc.isNull()
                            || (innerProc.isObject() && innerProc.isEmpty())) {
                        out.add(new SemanticError(
                                "ES-IP-004",
                                t("foreach processor requires an inner 'processor' definition",
                                        "foreach sin 'processor' interno"),
                                t("Add processor: { \"set\": { ... } } (or another processor) inside foreach",
                                        "Añada processor: { \"set\": { ... } } u otro processor dentro de foreach"),
                                SemanticError.Severity.ERROR));
                    }
                }
                case "script" -> {
                    if (!body.has("lang") || body.get("lang").isNull()
                            || (body.get("lang").isTextual() && body.get("lang").asText().isBlank())) {
                        out.add(new SemanticError(
                                "ES-IP-005",
                                t("script processor without lang defaults to painless — declare it explicitly",
                                        "Default es painless, declarar explícito"),
                                t("Set lang: 'painless' (or another supported lang) on the script processor",
                                        "Declare lang: 'painless' (u otro idioma soportado) en el processor script"),
                                SemanticError.Severity.INFO));
                    }
                }
                case "grok" -> {
                    if (grokPatternGroupCount(body) > 5) {
                        out.add(new SemanticError(
                                "ES-IP-006",
                                t("grok pattern with many capture groups is expensive at ingest time",
                                        "Performance: grok con pattern complicado (>5 grupos)"),
                                t("Simplify the pattern, reduce named captures, or split parsing across processors",
                                        "Simplifique el patrón o reparta el análisis entre varios processors"),
                                SemanticError.Severity.WARNING));
                    }
                }
                case "remove" -> collectRemovedFieldNames(body, removedFields);
                case "rename" -> {
                    JsonNode from = body.get("field");
                    if (from != null && from.isTextual()) {
                        String oldName = from.asText();
                        if (!oldName.isBlank() && removedFields.contains(oldName)) {
                            out.add(new SemanticError(
                                    "ES-IP-007",
                                    t("rename references field '" + oldName
                                                    + "' that was already removed earlier in the pipeline",
                                            "Campo ya no existe — 'remove' antes de 'rename' del mismo campo"),
                                    t("Reorder processors so rename runs before remove, or adjust field names",
                                            "Reordene processors para renombrar antes de eliminar, o ajuste los nombres"),
                                    SemanticError.Severity.ERROR));
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private static String firstIngestProcessorType(JsonNode step) {
        Iterator<String> it = step.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if ("if".equals(key) || "on_failure".equals(key) || "tag".equals(key) || "description".equals(key)) {
                continue;
            }
            if (INGEST_PROCESSOR_KEYS.contains(key)) {
                return key;
            }
        }
        return null;
    }

    private static void collectRemovedFieldNames(JsonNode removeBody, Set<String> into) {
        JsonNode field = removeBody.get("field");
        if (field == null || field.isNull()) {
            return;
        }
        if (field.isTextual()) {
            String s = field.asText().trim();
            if (!s.isEmpty()) {
                into.add(s);
            }
        } else if (field.isArray()) {
            for (JsonNode el : field) {
                if (el != null && el.isTextual()) {
                    String s = el.asText().trim();
                    if (!s.isEmpty()) {
                        into.add(s);
                    }
                }
            }
        }
    }

    private static int grokPatternGroupCount(JsonNode grokBody) {
        int max = 0;
        JsonNode patterns = grokBody.get("patterns");
        if (patterns != null && patterns.isArray()) {
            for (JsonNode p : patterns) {
                if (p != null && p.isTextual()) {
                    max = Math.max(max, countGrokCaptureTokens(p.asText()));
                }
            }
        }
        JsonNode single = grokBody.get("pattern");
        if (single != null && single.isTextual()) {
            max = Math.max(max, countGrokCaptureTokens(single.asText()));
        }
        return max;
    }

    /** Counts {@code %{...}} tokens in a grok pattern as a proxy for capture complexity. */
    private static int countGrokCaptureTokens(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return 0;
        }
        int count = 0;
        int i = 0;
        while (i < pattern.length()) {
            int j = pattern.indexOf("%{", i);
            if (j < 0) {
                break;
            }
            count++;
            i = j + 2;
        }
        return count;
    }

    private void analyzeReindexBody(JsonNode root, List<SemanticError> out) {
        if (!root.has("slices") || root.get("slices").isNull()) {
            out.add(new SemanticError(
                    "ES-RX-001",
                    t("Reindex without slices runs single-threaded on the coordinator — slow for large indices",
                            "Considera paralelización: reindex sin slices"),
                    t("Set slices: \"auto\" or a positive integer to parallelize reindex across shards",
                            "Use slices: \"auto\" o un entero positivo para paralelizar el reindex"),
                    SemanticError.Severity.WARNING));
        }

        JsonNode script = root.get("script");
        if (script != null && !script.isNull()) {
            String src = reindexScriptSource(script);
            if (src != null && !src.isBlank()) {
                String compact = src.replaceAll("\\s+", " ");
                boolean usesSource = compact.contains("ctx._source");
                boolean guardsOp = compact.contains("ctx.op");
                if (usesSource && !guardsOp) {
                    out.add(new SemanticError(
                            "ES-RX-002",
                            t("Reindex script uses ctx._source without checking ctx.op — risky on deletes/noops",
                                    "Script en reindex sin ctx._source guard (falta comprobar ctx.op)"),
                            t("Guard writes with if (ctx.op == 'index' || ctx.op == 'create') { ... }",
                                    "Proteja el acceso con if (ctx.op == 'index' || ctx.op == 'create') { ... }"),
                            SemanticError.Severity.WARNING));
                }
            }
        }

        JsonNode source = root.get("source");
        if (source != null && source.isObject() && !source.has("remote")) {
            out.add(new SemanticError(
                    "ES-RX-003",
                    t("Same-cluster reindex (no remote source) is a supported pattern for migrations",
                            "OK: reindex same-cluster sin remote"),
                    t("For cross-cluster copy, configure source.remote with the remote cluster alias",
                            "Para copia entre clústeres, configure source.remote con el alias del clúster remoto"),
                    SemanticError.Severity.INFO));
        }
    }

    private static String reindexScriptSource(JsonNode script) {
        if (script.isTextual()) {
            return script.asText();
        }
        if (!script.isObject()) {
            return null;
        }
        JsonNode s = script.get("source");
        if (s != null && s.isTextual()) {
            return s.asText();
        }
        return null;
    }

    private static final Pattern MUSTACHE_SECTION_OPEN =
            Pattern.compile("\\{\\{#\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\}\\}");

    private static boolean isRenderTemplateRestPath(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        return prefix.contains("_render/template");
    }

    private static boolean isSearchTemplateRestPath(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        return prefix.contains("_search/template");
    }

    private static boolean isStoredMustacheScriptPath(String prefix, JsonNode root) {
        if (prefix == null || !prefix.contains("_scripts/")) {
            return false;
        }
        return isMustacheScriptObject(root.get("script"));
    }

    private static boolean isMustacheScriptObject(JsonNode script) {
        if (script == null || !script.isObject()) {
            return false;
        }
        JsonNode lang = script.get("lang");
        return lang != null && lang.isTextual() && "mustache".equalsIgnoreCase(lang.asText().trim());
    }

    private void analyzeStoredMustacheSearchTemplate(JsonNode root, List<SemanticError> out) {
        JsonNode script = root.get("script");
        String src = mustacheTemplateSourceFromScript(script);
        if (src == null || src.isBlank()) {
            return;
        }
        emitSqlInjectionLikeMustache(src, out);
        emitMustacheOptionalSectionWithoutDefaultWarnings(src, out);
        if (!mustacheInterpolationRoots(src).isEmpty()) {
            out.add(new SemanticError(
                    "ES-TPL-004",
                    t("Use POST _render/template to validate this stored template with concrete params before production",
                            "Usa _render/template para verificar"),
                    t("Call POST _render/template (or POST _render/template/<id>) with the same params you use at search time",
                            "Llame a POST _render/template con los mismos params que en búsqueda"),
                    SemanticError.Severity.INFO));
        }
    }

    private void analyzeSearchTemplateInvocationBody(JsonNode root, List<SemanticError> out) {
        analyzeTemplateLikeRequest(root, out, false);
    }

    private void analyzeRenderTemplateBody(JsonNode root, List<SemanticError> out) {
        analyzeTemplateLikeRequest(root, out, true);
    }

    private void analyzeTemplateLikeRequest(JsonNode root, List<SemanticError> out, boolean renderApi) {
        JsonNode idNode = root.get("id");
        boolean hasId = idNode != null && idNode.isTextual() && !idNode.asText().isBlank();
        String inlineSrc = searchTemplateInlineSourceAsString(root);
        if (inlineSrc != null && !inlineSrc.isBlank()) {
            emitSqlInjectionLikeMustache(inlineSrc, out);
            emitMustacheOptionalSectionWithoutDefaultWarnings(inlineSrc, out);
        }
        if (hasId && (inlineSrc == null || inlineSrc.isBlank())) {
            out.add(new SemanticError(
                    "ES-TPL-004",
                    t("Search template is referenced by id only — static validation of params is not possible here",
                            "Validación faltante: plantilla por id sin source en línea"),
                    t("Use POST _render/template to verify rendering and required params against the stored script",
                            "Usa _render/template para verificar"),
                    SemanticError.Severity.INFO));
        }
        if (inlineSrc == null || inlineSrc.isBlank()) {
            return;
        }
        Set<String> usedRoots = mustacheInterpolationRoots(inlineSrc);
        if (usedRoots.isEmpty()) {
            if (renderApi) {
                boolean hadUnused = emitUnusedRenderParamsWarningIfNeeded(root, out, Set.of());
                JsonNode params = root.get("params");
                boolean paramsEmpty = params == null || !params.isObject() || params.size() == 0;
                if (!hadUnused && paramsEmpty && !containsSqlInjectionLikePattern(inlineSrc)) {
                    emitRenderTemplateParamsCompleteInfo(out);
                }
            }
            return;
        }
        Set<String> provided = jsonObjectTopLevelKeys(root.get("params"));
        List<String> missing = new ArrayList<>();
        for (String key : usedRoots) {
            if (!provided.contains(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            out.add(new SemanticError(
                    "ES-TPL-001",
                    t("Mustache template expects params that are missing or empty in 'params': "
                                    + String.join(", ", missing),
                            "Plantilla mustache sin todos los params requeridos: " + String.join(", ", missing)),
                    t("Add every referenced name as a key under params, or provide defaults with {{^name}}...{{/name}} in the template",
                            "Declare cada nombre en params o use bloques {{^nombre}}...{{/nombre}} para valores por defecto"),
                    SemanticError.Severity.ERROR));
        }
        if (renderApi) {
            boolean hadUnused = emitUnusedRenderParamsWarningIfNeeded(root, out, usedRoots);
            if (missing.isEmpty() && !hadUnused && !containsSqlInjectionLikePattern(inlineSrc)) {
                emitRenderTemplateParamsCompleteInfo(out);
            }
        }
    }

    /** @return true if ES-RND-002 was emitted (unused param keys present). */
    private boolean emitUnusedRenderParamsWarningIfNeeded(JsonNode root, List<SemanticError> out, Set<String> usedRoots) {
        JsonNode params = root.get("params");
        if (params == null || !params.isObject()) {
            return false;
        }
        Set<String> provided = jsonObjectTopLevelKeys(params);
        List<String> unused = new ArrayList<>();
        for (String k : provided) {
            if (!usedRoots.contains(k)) {
                unused.add(k);
            }
        }
        if (!unused.isEmpty()) {
            out.add(new SemanticError(
                    "ES-RND-002",
                    t("Render params include keys not referenced by the template: " + String.join(", ", unused),
                            "Params no usados: " + String.join(", ", unused)),
                    t("Remove unused keys from params to keep requests explicit and avoid confusion",
                            "Elimine claves no usadas en params para mantener la petición explícita"),
                    SemanticError.Severity.WARNING));
            return true;
        }
        return false;
    }

    private void emitRenderTemplateParamsCompleteInfo(List<SemanticError> out) {
        out.add(new SemanticError(
                "ES-RND-001",
                t("Render template request supplies every referenced param with no unused keys — good for dry-runs and CI checks",
                        "Render con todos los params: plantilla y params alineados (sin claves sobrantes)"),
                t("Keep validating stored templates (referenced by id) with the same params via POST _render/template before production",
                            "Siga validando plantillas almacenadas (por id) con POST _render/template antes de producción"),
                SemanticError.Severity.INFO));
    }

    private static Set<String> jsonObjectTopLevelKeys(JsonNode params) {
        if (params == null || !params.isObject()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        Iterator<String> it = params.fieldNames();
        while (it.hasNext()) {
            keys.add(it.next());
        }
        return keys;
    }

    private static String mustacheTemplateSourceFromScript(JsonNode script) {
        if (script == null || !script.isObject()) {
            return null;
        }
        JsonNode src = script.get("source");
        if (src == null || src.isNull()) {
            return null;
        }
        if (src.isTextual()) {
            return src.asText();
        }
        try {
            return MAPPER.writeValueAsString(src);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String searchTemplateInlineSourceAsString(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode src = root.get("source");
        if (src == null || src.isNull()) {
            return null;
        }
        if (src.isTextual()) {
            return src.asText();
        }
        try {
            return MAPPER.writeValueAsString(src);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void emitSqlInjectionLikeMustache(String src, List<SemanticError> out) {
        if (!containsSqlInjectionLikePattern(src)) {
            return;
        }
        out.add(new SemanticError(
                "ES-TPL-003",
                t("Mustache template text contains patterns that resemble SQL injection or unsafe dynamic query assembly",
                        "Patrones tipo SQL injection en plantilla mustache"),
                t("Parameterize with structured JSON fields instead of concatenating user text into query clauses",
                        "Use campos JSON estructurados en lugar de concatenar texto de usuario en la consulta"),
                SemanticError.Severity.ERROR));
    }

    private static boolean containsSqlInjectionLikePattern(String src) {
        if (src == null || src.isEmpty()) {
            return false;
        }
        String lower = src.toLowerCase(Locale.ROOT);
        if (lower.contains(" or 1=1") || lower.contains(" or 1 = 1")) {
            return true;
        }
        if (lower.contains("' or '") || lower.contains("\" or \"")) {
            return true;
        }
        if (lower.contains("union select") || lower.contains("union all select")) {
            return true;
        }
        if (lower.contains("; drop ") || lower.contains("; delete ") || lower.contains("; truncate ")) {
            return true;
        }
        if (lower.contains("--") && lower.contains("select")) {
            return true;
        }
        if (lower.contains("@@version") || lower.contains("sleep(") || lower.contains("benchmark(")) {
            return true;
        }
        if (lower.contains("1=1") && lower.contains("where")) {
            return true;
        }
        return false;
    }

    private void emitMustacheOptionalSectionWithoutDefaultWarnings(String src, List<SemanticError> out) {
        Set<String> seen = new HashSet<>();
        List<String> missingDefault = new ArrayList<>();
        var m = MUSTACHE_SECTION_OPEN.matcher(src);
        while (m.find()) {
            String name = m.group(1);
            if (name == null || name.isEmpty() || seen.contains(name)) {
                continue;
            }
            seen.add(name);
            if (!hasMustacheInvertedSectionFor(src, name)) {
                missingDefault.add(name);
            }
        }
        if (!missingDefault.isEmpty()) {
            out.add(new SemanticError(
                    "ES-TPL-002",
                    t("Mustache section parameters lack default inverted blocks ({{^name}}) for: "
                                    + String.join(", ", missingDefault),
                            "Params opcionales sin defaults para: " + String.join(", ", missingDefault)),
                    t("Add inverted sections {{^name}}...{{/name}} for each optional parameter that may be missing",
                            "Añada {{^nombre}}...{{/nombre}} para cada param opcional que pueda faltar"),
                    SemanticError.Severity.WARNING));
        }
    }

    private static boolean hasMustacheInvertedSectionFor(String src, String name) {
        String esc = Pattern.quote(name);
        return Pattern.compile("\\{\\{\\^\\s*" + esc + "\\s*\\}\\}").matcher(src).find();
    }

    /**
     * Root names used in Mustache interpolations such as {@code {{foo}}}, {@code {{{bar}}}}, or
     * {@code {{foo.field}}} (root key {@code foo}).
     */
    private static Set<String> mustacheInterpolationRoots(String source) {
        Set<String> roots = new LinkedHashSet<>();
        if (source == null || source.isEmpty()) {
            return roots;
        }
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) != '{') {
                continue;
            }
            if (i + 1 >= source.length() || source.charAt(i + 1) != '{') {
                continue;
            }
            boolean triple = i + 2 < source.length() && source.charAt(i + 2) == '{';
            String closeSeq = triple ? "}}}" : "}}";
            int innerStart = i + (triple ? 3 : 2);
            int closeIdx = source.indexOf(closeSeq, innerStart);
            if (closeIdx < 0) {
                break;
            }
            String inner = source.substring(innerStart, closeIdx).trim();
            if (!inner.isEmpty()) {
                char c0 = inner.charAt(0);
                if (c0 != '!' && c0 != '#' && c0 != '^' && c0 != '/' && c0 != '>') {
                    String token = firstMustacheInterpolationToken(inner);
                    if (token != null && !token.isEmpty() && token.charAt(0) != '=') {
                        int dot = token.indexOf('.');
                        String root = dot < 0 ? token : token.substring(0, dot);
                        if (root.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                            roots.add(root);
                        }
                    }
                }
            }
            i = closeIdx + closeSeq.length() - 1;
        }
        return roots;
    }

    private static String firstMustacheInterpolationToken(String inner) {
        String s = inner.strip();
        int cut = s.length();
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (Character.isWhitespace(ch) || ch == '|') {
                cut = k;
                break;
            }
        }
        return s.substring(0, cut).trim();
    }

    private enum SnapshotPathKind {
        NONE,
        REPOSITORY,
        CREATE,
        RESTORE,
        STATUS
    }

    /** Text before the first {@code '{'} on the same request (method + path), lowercased. */
    private static String extractRestPrefix(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.strip();
        int brace = s.indexOf('{');
        if (brace <= 0) {
            return s.toLowerCase(Locale.ROOT);
        }
        return s.substring(0, brace).strip().toLowerCase(Locale.ROOT);
    }

    private static SnapshotPathKind classifySnapshotPath(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return SnapshotPathKind.NONE;
        }
        String p = prefix;
        int snap = p.indexOf("_snapshot/");
        if (snap < 0) {
            return SnapshotPathKind.NONE;
        }
        String tail = p.substring(snap + "_snapshot/".length()).trim();
        int sp = tail.indexOf(' ');
        if (sp > 0) {
            tail = tail.substring(0, sp).trim();
        }
        if (tail.isEmpty()) {
            return SnapshotPathKind.NONE;
        }
        String[] parts = tail.split("/");
        if (parts.length == 0) {
            return SnapshotPathKind.NONE;
        }
        String last = parts[parts.length - 1];
        if ("_restore".equals(last)) {
            return SnapshotPathKind.RESTORE;
        }
        if ("_status".equals(last)) {
            return SnapshotPathKind.STATUS;
        }
        if (parts.length == 1) {
            return SnapshotPathKind.REPOSITORY;
        }
        return SnapshotPathKind.CREATE;
    }

    private static boolean isIlmPolicyBody(JsonNode root, String prefix) {
        if (root == null || !root.isObject()) {
            return false;
        }
        String p = prefix != null ? prefix : "";
        if (p.contains("_ilm/policy")) {
            return true;
        }
        JsonNode pol = root.get("policy");
        if (pol != null && pol.isObject() && pol.has("phases")) {
            return looksLikeIlmPhases(pol.get("phases"));
        }
        JsonNode phases = root.get("phases");
        return phases != null && phases.isObject() && looksLikeIlmPhases(phases);
    }

    private static boolean looksLikeIlmPhases(JsonNode phases) {
        if (phases == null || !phases.isObject() || phases.isEmpty()) {
            return false;
        }
        Set<String> std = Set.of("hot", "warm", "cold", "delete", "frozen", "new", "unfollow");
        Iterator<String> it = phases.fieldNames();
        while (it.hasNext()) {
            String name = it.next();
            if (std.contains(name)) {
                return true;
            }
            JsonNode ch = phases.get(name);
            if (ch != null && ch.isObject() && ch.has("actions")) {
                return true;
            }
        }
        return false;
    }

    private void analyzeIlmPolicy(JsonNode root, List<SemanticError> out) {
        JsonNode phases = root.path("policy").path("phases");
        if (!phases.isObject() || !looksLikeIlmPhases(phases)) {
            phases = root.path("phases");
        }
        if (!phases.isObject() || !looksLikeIlmPhases(phases)) {
            return;
        }

        if (!phases.has("delete")) {
            out.add(new SemanticError(
                    "ES-ILM-005",
                    t("ILM policy has no delete phase — indices may grow indefinitely",
                            "Policy sin phase delete — indices crecen indefinidamente"),
                    t("Add a delete phase (or managed rollover cleanup) so old data is removed on schedule",
                            "Añada una fase delete (o limpieza gestionada) para eliminar datos antiguos"),
                    SemanticError.Severity.WARNING));
        }

        if (phases.has("hot")) {
            JsonNode hot = phases.get("hot");
            JsonNode actions = hot != null ? hot.path("actions") : null;
            boolean hasRollover = actions != null && actions.isObject()
                    && actions.has("rollover")
                    && !actions.get("rollover").isNull();
            if (!hasRollover) {
                out.add(new SemanticError(
                        "ES-ILM-001",
                        t("Hot phase has no rollover action — index growth may be unbounded",
                                "Hot phase sin rollover action"),
                        t("Add actions.rollover (max_age / max_size / max_docs) on the hot phase",
                                "Añada actions.rollover (max_age, max_size o max_docs) en la fase hot"),
                        SemanticError.Severity.WARNING));
            }
        }

        if (phases.has("delete")) {
            JsonNode del = phases.get("delete");
            if (del != null && del.isObject()) {
                JsonNode minAge = del.get("min_age");
                boolean missingMinAge = !del.has("min_age") || minAge == null || minAge.isNull()
                        || (minAge.isTextual() && minAge.asText().isBlank());
                if (missingMinAge) {
                    out.add(new SemanticError(
                            "ES-ILM-002",
                            t("Delete phase has no retention window (min_age) — deletes may run too early",
                                    "Delete phase sin retention period"),
                            t("Set min_age on the delete phase (e.g. \"90d\") to define when data is removed",
                                    "Defina min_age en la fase delete (p.ej. \"90d\") como periodo de retención"),
                            SemanticError.Severity.ERROR));
                }
            }
        }

        if (phases.has("warm")) {
            JsonNode warm = phases.get("warm");
            JsonNode actions = warm != null ? warm.path("actions") : null;
            if (actions != null && actions.isObject() && actions.has("shrink") && actions.has("forcemerge")) {
                int shrinkPos = -1;
                int fmPos = -1;
                int i = 0;
                Iterator<String> fn = actions.fieldNames();
                while (fn.hasNext()) {
                    String k = fn.next();
                    if ("shrink".equals(k)) {
                        shrinkPos = i;
                    }
                    if ("forcemerge".equals(k)) {
                        fmPos = i;
                    }
                    i++;
                }
                if (shrinkPos >= 0 && fmPos >= 0 && shrinkPos < fmPos) {
                    out.add(new SemanticError(
                            "ES-ILM-003",
                            t("Warm phase lists shrink before forcemerge — suboptimal order for maintenance",
                                    "Orden subóptimo: shrink antes de force_merge en warm"),
                            t("Prefer forcemerge before shrink in the warm phase action list for clarity and typical ILM flow",
                                    "Prefiera forcemerge antes de shrink en la lista de acciones warm"),
                            SemanticError.Severity.WARNING));
                }
            }
        }

        if (phases.has("cold")) {
            JsonNode cold = phases.get("cold");
            JsonNode actions = cold != null ? cold.path("actions") : null;
            if (actions != null && actions.isObject() && !actions.has("freeze")) {
                out.add(new SemanticError(
                        "ES-ILM-004",
                        t("Cold phase has no freeze action — memory use may stay higher than necessary",
                            "Cold phase sin freeze"),
                        t("Consider adding freeze in the cold phase to reduce heap pressure on frozen indices",
                                "Considera freeze para ahorrar memoria en índices congelados"),
                        SemanticError.Severity.INFO));
            }
        }
    }

    private static boolean isSnapshotRepositoryBody(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (root.has("indices") || root.has("processors")) {
            return false;
        }
        if (root.has("policy") && root.path("policy").has("phases")) {
            return false;
        }
        JsonNode src = root.get("source");
        JsonNode dst = root.get("dest");
        if (src != null && src.isObject() && dst != null && dst.isObject()) {
            return false;
        }
        JsonNode type = root.get("type");
        JsonNode settings = root.get("settings");
        return type != null && type.isTextual() && !type.asText().isBlank()
                && settings != null && settings.isObject();
    }

    private static boolean isSnapshotRestoreBody(JsonNode root, String prefix) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (isReindexBody(root) || isIngestPipelineBody(root)) {
            return false;
        }
        String p = prefix != null ? prefix : "";
        if (p.contains("_snapshot") && p.contains("_restore")) {
            return true;
        }
        if (!root.has("indices")) {
            return false;
        }
        if (root.has("rename_pattern") || root.has("rename_replacement")) {
            return true;
        }
        return root.has("index_settings") || root.has("ignore_index_settings");
    }

    private static boolean isSnapshotCreateBody(JsonNode root, String prefix) {
        if (root == null || !root.isObject()) {
            return false;
        }
        if (isReindexBody(root) || isIngestPipelineBody(root) || isSnapshotRepositoryBody(root)) {
            return false;
        }
        if (isSnapshotRestoreBody(root, prefix)) {
            return false;
        }
        if (classifySnapshotPath(prefix) == SnapshotPathKind.CREATE) {
            return true;
        }
        if (!root.has("indices")) {
            return false;
        }
        if (root.has("query")) {
            return false;
        }
        return root.has("partial")
                || root.has("feature_states")
                || (root.has("metadata") && root.get("metadata").isObject())
                || root.has("ignore_unavailable")
                || root.has("wait_for_completion");
    }

    private void analyzeSnapshotRepository(JsonNode root, List<SemanticError> out) {
        JsonNode settings = root.path("settings");
        boolean compress = settings.path("compress").asBoolean(false);
        if (!compress) {
            out.add(new SemanticError(
                    "ES-SNP-001",
                    t("Snapshot repository does not set compress: true — snapshots may use more disk space",
                            "Snapshot sin compress: true (repositorio)"),
                    t("Set settings.compress: true on the repository for smaller snapshot files where supported",
                            "Use settings.compress: true en el repositorio para reducir tamaño si el tipo lo soporta"),
                    SemanticError.Severity.INFO));
        }

        boolean hasVerifyScheduleHint = root.has("verify_after_inactivity")
                || settings.has("verify_after_inactivity")
                || root.has("schedule");
        if (!hasVerifyScheduleHint) {
            out.add(new SemanticError(
                    "ES-SNP-003",
                    t("Repository definition has no verification schedule — stale or broken repos may go unnoticed",
                            "Repository sin verify schedule"),
                    t("Periodically call POST _snapshot/<repo>/_verify or use SLM with a cron schedule",
                            "Programe POST _snapshot/<repo>/_verify o políticas SLM con schedule"),
                    SemanticError.Severity.INFO));
        }
    }

    private void analyzeSnapshotCreate(JsonNode root, List<SemanticError> out) {
        JsonNode inc = root.get("include_global_state");
        if (inc != null && inc.isBoolean() && inc.asBoolean()) {
            out.add(new SemanticError(
                    "ES-SNP-004",
                    t("Snapshot uses include_global_state: true — restoring can overwrite cluster metadata in production",
                            "Snapshot include_global_state: true en producción"),
                    t("Prefer include_global_state: false unless you intentionally snapshot global state",
                            "Prefiera false salvo que necesite restaurar metadatos globales a propósito"),
                    SemanticError.Severity.WARNING));
        }
    }

    private void analyzeSnapshotRestore(JsonNode root, List<SemanticError> out) {
        JsonNode rp = root.get("rename_pattern");
        boolean hasPattern = rp != null && !rp.isNull() && rp.isTextual() && !rp.asText().isBlank();
        if (!hasPattern) {
            out.add(new SemanticError(
                    "ES-SNP-002",
                    t("Restore request has no rename_pattern — existing index names may be overwritten",
                            "Sobreescribirá indices existentes: restore sin rename_pattern"),
                    t("Set rename_pattern and rename_replacement to map restored indices to new names",
                            "Use rename_pattern y rename_replacement para mapear índices restaurados a nombres nuevos"),
                    SemanticError.Severity.WARNING));
        }
    }

    private enum EsCrossBlockKind {
        INGEST,
        REINDEX,
        SEARCH,
        ILM,
        SNAPSHOT,
        ES_TEMPLATE
    }

    private static EsCrossBlockKind classifyElasticsearchJsonBlock(JsonNode root, String rawBlock) {
        String prefix = extractRestPrefix(rawBlock != null ? rawBlock : "");
        if (isRenderTemplateRestPath(prefix)) {
            return EsCrossBlockKind.ES_TEMPLATE;
        }
        if (isSearchTemplateRestPath(prefix)) {
            return EsCrossBlockKind.ES_TEMPLATE;
        }
        if (isStoredMustacheScriptPath(prefix, root)) {
            return EsCrossBlockKind.ES_TEMPLATE;
        }
        if (isIlmPolicyBody(root, prefix)) {
            return EsCrossBlockKind.ILM;
        }
        if (isIngestPipelineBody(root)) {
            return EsCrossBlockKind.INGEST;
        }
        if (isReindexBody(root)) {
            return EsCrossBlockKind.REINDEX;
        }
        if (classifySnapshotPath(prefix) != SnapshotPathKind.NONE
                || isSnapshotRepositoryBody(root)
                || isSnapshotRestoreBody(root, prefix)
                || isSnapshotCreateBody(root, prefix)) {
            return EsCrossBlockKind.SNAPSHOT;
        }
        return EsCrossBlockKind.SEARCH;
    }

    /**
     * Cross-script analysis for multi-statement Elasticsearch inputs. Splits with
     * {@link StatementSplitter} using {@link SqlDialect#GENERIC} (same as {@code analyzeMultiStatement}).
     * Each fragment may be a search body, ingest pipeline JSON, or reindex body; REST prefixes such as
     * {@code PUT _ingest/pipeline/...} before the JSON object are stripped via {@link #extractJsonPayload}.
     */
    public List<SemanticError> analyzeCrossScriptOnly(String rawScript, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;

        if (rawScript == null || rawScript.isBlank()) {
            return out;
        }

        List<String> blocks = StatementSplitter.split(rawScript.strip(), SqlDialect.GENERIC);
        int validJsonBlocks = 0;
        int queriesWithoutSize = 0;
        int aggregationsOnly = 0;
        int queriesWithSearch = 0;
        int ingestBlocks = 0;
        int reindexBlocks = 0;
        int searchBlocks = 0;
        int ilmBlocks = 0;
        int snapshotBlocks = 0;
        int templateBlocks = 0;

        for (String block : blocks) {
            String trimmed = block.strip();
            if (trimmed.isBlank()) {
                continue;
            }
            String payload = extractJsonPayload(trimmed);
            if (payload.isBlank() || !payload.startsWith("{")) {
                continue;
            }

            try {
                JsonNode node = MAPPER.readTree(payload);
                validJsonBlocks++;

                EsCrossBlockKind kind = classifyElasticsearchJsonBlock(node, trimmed);
                switch (kind) {
                    case INGEST -> ingestBlocks++;
                    case REINDEX -> reindexBlocks++;
                    case SEARCH -> searchBlocks++;
                    case ILM -> ilmBlocks++;
                    case SNAPSHOT -> snapshotBlocks++;
                    case ES_TEMPLATE -> templateBlocks++;
                }

                if (kind == EsCrossBlockKind.SEARCH) {
                    if (node.has("query") && !node.has("size")) {
                        queriesWithoutSize++;
                    }
                    boolean hasAggs = node.has("aggs") || node.has("aggregations");
                    boolean hasQuery = node.has("query");
                    if (hasAggs && !hasQuery) {
                        aggregationsOnly++;
                    }
                    if (hasQuery) {
                        queriesWithSearch++;
                    }
                }
            } catch (JsonProcessingException ignored) {
                // Malformed blocks are reported per-fragment by analyze(); skip here
            }
        }

        if (validJsonBlocks <= 1) {
            return out;
        }

        if (queriesWithoutSize >= 2) {
            out.add(new SemanticError(
                    "ES-CROSS-001",
                    t("Multiple queries in this script have no 'size' limit — "
                                    + "this may retrieve large result sets unintentionally.",
                            "Varias queries en este script no tienen límite 'size' — "
                                    + "podrían recuperar grandes volúmenes de resultados."),
                    t("Add 'size' to each query to control the result window.",
                            "Añade 'size' a cada query para controlar el volumen de resultados."),
                    SemanticError.Severity.WARNING));
        }

        if (aggregationsOnly > 0 && queriesWithSearch > 0) {
            out.add(new SemanticError(
                    "ES-CROSS-002",
                    t("Script mixes aggregation-only queries with search queries. "
                                    + "Verify each query has the correct intent.",
                            "El script mezcla queries solo de agregación con queries de búsqueda. "
                                    + "Verifica que cada query tenga la intención correcta."),
                    t("Separate aggregation and search queries if they serve different purposes.",
                            "Separa queries de agregación y de búsqueda si tienen propósitos distintos."),
                    SemanticError.Severity.INFO));
        }

        int distinctApiKinds = (ingestBlocks > 0 ? 1 : 0)
                + (reindexBlocks > 0 ? 1 : 0)
                + (searchBlocks > 0 ? 1 : 0)
                + (ilmBlocks > 0 ? 1 : 0)
                + (snapshotBlocks > 0 ? 1 : 0)
                + (templateBlocks > 0 ? 1 : 0);
        if (distinctApiKinds >= 2) {
            out.add(new SemanticError(
                    "ES-CROSS-003",
                    t("Script mixes Elasticsearch API payloads (search, ingest pipeline, reindex, ILM, snapshots, templates). "
                                    + "Confirm each statement is sent to the correct HTTP endpoint.",
                            "El script mezcla tipos de API de Elasticsearch (búsqueda, ingest, reindex, ILM, snapshots, plantillas). "
                                    + "Confirme que cada sentencia vaya al endpoint HTTP correcto."),
                    t("Split into separate requests per API (/_search, /_ingest/pipeline, /_reindex, /_ilm/policy, /_snapshot, /_search/template, /_render/template).",
                            "Separe peticiones por API (/_search, /_ingest/pipeline, /_reindex, /_ilm/policy, /_snapshot, /_search/template, /_render/template)."),
                    SemanticError.Severity.INFO));
        }

        if (ingestBlocks >= 2) {
            out.add(new SemanticError(
                    "ES-CROSS-004",
                    t("Multiple ingest pipeline definitions in one script — cluster applies them independently; "
                                    + "order of PUT calls matters only for your operational flow.",
                            "Varias definiciones de ingest pipeline en un script — el orden de las peticiones "
                                    + "solo afecta a su flujo operativo, no a encadenamiento automático."),
                    t("Document pipeline dependencies (nested pipeline processors) in your runbook or CI.",
                            "Documente dependencias entre pipelines (processor pipeline) en runbook o CI."),
                    SemanticError.Severity.INFO));
        }

        return out;
    }
}
