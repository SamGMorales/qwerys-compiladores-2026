package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.qwerys.qwerys_backend.analyzer.nosql.CassandraJsParser;

/**
 * Semantic analysis for Apache Cassandra CQL, including BATCH, LWT, collections, materialized views,
 * indexes, UDT hints, TTL/time functions, UDF/UDA, and triggers.
 *
 * <p>BATCH / LWT product codes per Day 24F: {@code CAS-BATCH-001}–{@code CAS-BATCH-005},
 * {@code CAS-LWT-001}–{@code CAS-LWT-003}. Other CQL-BATCH-* / CQL-LWT-* codes remain for auxiliary rules.
 *
 * <p>UDF / UDA / trigger codes per Day 24G: {@code CAS-UDF-001}–{@code CAS-UDF-003},
 * {@code CAS-UDA-001}–{@code CAS-UDA-002}, {@code CAS-TRG-001}–{@code CAS-TRG-002}.
 *
 * <p>DCL / auth codes per Day 24H: {@code CAS-DCL-001}–{@code CAS-DCL-005}, {@code CAS-AUTH-001}.
 *
 * <p>Works on tokens from {@link CqlLexer}. Findings use {@link SemanticError}; the service layer
 * maps {@link SemanticError.Severity#ERROR} to {@link com.qwerys.qwerys_backend.model.AnalysisError}.
 */
public final class CqlAnalyzer {

    private static final Set<String> PRIMITIVE_TYPES = buildPrimitives();

    private Locale ui = Locale.ENGLISH;

    private String msg(String en, String es) {
        return AnalysisMessages.t(ui, en, es);
    }

    public List<SemanticError> analyze(String raw) {
        return analyze(raw, Locale.ENGLISH);
    }

    public List<SemanticError> analyze(String raw, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;
        if (raw == null || raw.isBlank()) {
            out.add(new SemanticError(
                    "CQL-EMPTY",
                    msg("Empty CQL statement", "Consulta CQL vacía"),
                    msg("Provide a CQL query to analyze.", "Escriba una consulta CQL para analizar."),
                    SemanticError.Severity.ERROR));
            return out;
        }

        List<String> statements = StatementSplitter.split(raw.strip(), SqlDialect.GENERIC);
        SchemaRegistry registry = new SchemaRegistry();
        for (String stmt : statements) {
            String one = stmt.strip();
            if (!one.isEmpty()) {
                registry.ingestStatement(one);
            }
        }

        int listMutationOps = 0;
        for (String stmt : statements) {
            String one = stmt.strip();
            if (!one.isEmpty()) {
                try {
                    listMutationOps += countPlusMinusAssignOps(new CqlLexer(one).tokenize());
                } catch (CqlLexer.LexException ignored) {
                }
            }
        }
        if (listMutationOps >= 3) {
            out.add(new SemanticError(
                    "CQL-LIST-001",
                    msg("Frequent list mutations are expensive in Cassandra; consider using set or map instead",
                            "Mutaciones frecuentes de list son costosas en Cassandra; considere set o map"),
                    msg("Prefer SET or MAP collections, or model one-to-many with clustering columns.",
                            "Prefiera colecciones SET o MAP, o modele 1:N con columnas de clustering."),
                    SemanticError.Severity.WARNING));
        }

        for (String stmt : statements) {
            String one = stmt.strip();
            if (one.isEmpty()) {
                continue;
            }
            analyzeOne(one, out, registry, uiLocale);
        }
        return out;
    }

    /**
     * Script-wide CQL rules for multi-statement analysis (e.g. list mutation frequency across fragments).
     */
    public List<SemanticError> scriptWideSemanticFindings(List<String> orderedStatements, Locale uiLocale) {
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;
        List<SemanticError> out = new ArrayList<>();
        if (orderedStatements == null || orderedStatements.isEmpty()) {
            return out;
        }
        int listMutationOps = 0;
        for (String stmt : orderedStatements) {
            if (stmt == null || stmt.isBlank()) {
                continue;
            }
            try {
                listMutationOps += countPlusMinusAssignOps(new CqlLexer(stmt.strip()).tokenize());
            } catch (CqlLexer.LexException ignored) {
            }
        }
        if (listMutationOps >= 3) {
            out.add(new SemanticError(
                    "CQL-LIST-001",
                    msg("Frequent list mutations are expensive in Cassandra; consider using set or map instead",
                            "Mutaciones frecuentes de list son costosas en Cassandra; considere set o map"),
                    msg("Prefer SET or MAP collections, or model one-to-many with clustering columns.",
                            "Prefiera colecciones SET o MAP, o modele 1:N con columnas de clustering."),
                    SemanticError.Severity.WARNING));
        }
        return out;
    }

    /**
     * Ingests CREATE statements from the full script into a registry, then analyzes one fragment with
     * the same cross-statement schema context as a single {@link #analyze(String, Locale)} call.
     */
    public List<SemanticError> ingestFullScriptAndAnalyzeOne(
            List<String> orderedStatements, String statementToAnalyze, Locale uiLocale) {
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;
        SchemaRegistry registry = new SchemaRegistry();
        if (orderedStatements != null) {
            for (String s : orderedStatements) {
                if (s != null && !s.isBlank()) {
                    registry.ingestStatement(s.strip());
                }
            }
        }
        List<SemanticError> out = new ArrayList<>();
        String one = statementToAnalyze != null ? statementToAnalyze.strip() : "";
        if (one.isEmpty()) {
            return out;
        }
        analyzeOne(one, out, registry, uiLocale);
        return out;
    }

    private void analyzeOne(String stmt, List<SemanticError> out, SchemaRegistry registry, Locale uiLocale) {
        List<CqlToken> tokens;
        try {
            tokens = new CqlLexer(stmt).tokenize();
        } catch (CqlLexer.LexException ex) {
            String raw = ex.detailMessage();
            String messageText = raw != null && !raw.isBlank()
                    ? raw
                    : msg("CQL syntax error.", "Error de sintaxis en el query CQL.");
            String sug = msg("Review CQL syntax.", "Revise la sintaxis CQL.");
            out.add(new SemanticError(
                    "CQL-SYN-001",
                    messageText,
                    sug,
                    SemanticError.Severity.ERROR,
                    ex.line(),
                    ex.column()));
            return;
        }

        int i = firstSignificant(tokens);
        if (i < 0 || tokens.get(i).type() == CqlTokenType.EOF) {
            return;
        }
        CqlToken start = tokens.get(i);

        if (start.type() != CqlTokenType.KEYWORD) {
            out.add(new SemanticError(
                    "CQL-STRUCT-START",
                    msg("CQL statement should begin with a keyword (SELECT, INSERT, UPDATE, DELETE, CREATE, …)",
                            "La sentencia CQL debe empezar con una palabra clave (SELECT, INSERT, UPDATE, DELETE, CREATE, …)"),
                    msg("Start the statement with a valid CQL command.",
                            "Inicie la sentencia con un comando CQL válido."),
                    SemanticError.Severity.ERROR));
            return;
        }

        String kw = start.value().toUpperCase(Locale.ROOT);
        switch (kw) {
            case "BEGIN" -> analyzeBatch(stmt, tokens, out, registry);
            case "SELECT" -> {
                analyzeSelect(tokens, out, registry);
                if (!hasWhereAtDepthZero(tokens)) {
                    out.add(new SemanticError(
                            "CQL-FULLSCAN-001",
                            msg("SELECT without WHERE in Cassandra scans all partitions",
                                    "SELECT sin WHERE en Cassandra escanea todas las particiones"),
                            msg("Add a WHERE clause using the partition key to restrict the scan",
                                    "Añada WHERE con la clave de partición para acotar el escaneo"),
                            SemanticError.Severity.WARNING));
                }
            }
            case "INSERT" -> analyzeInsert(tokens, out, registry);
            case "UPDATE" -> analyzeUpdate(tokens, out, registry);
            case "DELETE" -> analyzeDelete(tokens, out, registry);
            case "CREATE" -> analyzeCreate(tokens, out, registry);
            case "ALTER" -> analyzeAlter(tokens, out);
            case "DROP" -> analyzeDropStatement(tokens, out);
            case "GRANT" -> analyzeDclGrant(tokens, out, registry);
            case "REVOKE" -> analyzeDclRevoke(tokens, out, registry);
            case "LIST" -> analyzeDclList(tokens, out);
            case "LOGIN", "LOGOUT" -> analyzeDclAuthShell(tokens, out);
            default -> {
            }
        }

        applyGlobalRules(tokens, out, registry);
    }

    private void analyzeBatch(String rawStmt, List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        if (!isBeginBatchStatement(tokens)) {
            return;
        }
        if (!endsWithApplyBatch(tokens)) {
            out.add(new SemanticError(
                    "CQL-BATCH-APPLY",
                    msg("BATCH must end with APPLY BATCH", "BATCH debe terminar con APPLY BATCH"),
                    msg("Terminate the batch with APPLY BATCH before the semicolon.",
                            "Termine el batch con APPLY BATCH antes del punto y coma."),
                    SemanticError.Severity.ERROR));
        }
        int est = estimateBatchStatementCount(tokens);
        if (est > 50) {
            out.add(new SemanticError(
                    "CAS-BATCH-001",
                    msg("Critical performance risk: BATCH with more than 50 statements",
                            "Riesgo crítico de rendimiento: BATCH con más de 50 sentencias"),
                    msg("Split into smaller batches or use async writes; coordinator overhead grows quickly.",
                            "Divida en batches más pequeños o use escrituras asíncronas; el coordinador se satura."),
                    SemanticError.Severity.ERROR));
        } else if (est > 100) {
            out.add(new SemanticError(
                    "CQL-BATCH-LARGE",
                    msg("Large batches are anti-pattern in Cassandra; use asynchronous writes instead",
                            "Batches muy grandes son anti-patrón en Cassandra; use escrituras asíncronas"),
                    msg("Split work into smaller batches or use async drivers with concurrency limits.",
                            "Parta el trabajo en batches más pequeños o use drivers async con límites de concurrencia."),
                    SemanticError.Severity.WARNING));
        }

        PartitionBatchAnalysis pba = analyzePartitionKeysInBatch(tokens, registry);
        if (pba.crossPartition) {
            out.add(new SemanticError(
                    "CAS-BATCH-002",
                    msg("BATCH spans multiple partition keys — anti-pattern, not atomic across nodes",
                            "BATCH cruza varias claves de partición — anti-patrón, no atómico entre nodos"),
                    msg("Keep a logged batch to a single partition, or split statements per partition.",
                            "Mantenga el batch en una sola partición o separe sentencias por partición."),
                    SemanticError.Severity.WARNING));
        }

        if (isBeginUnloggedBatch(tokens)) {
            Set<String> tables = extractDmlTablesInBatch(tokens);
            if (tables.size() > 1) {
                out.add(new SemanticError(
                        "CQL-BATCH-003",
                        msg("Unlogged batches spanning multiple tables lose atomicity; consider separate statements",
                                "Batches UNLOGGED en varias tablas pierden atomicidad; use sentencias separadas"),
                        msg("Restrict UNLOGGED BATCH to a single table/partition or use separate calls.",
                                "Limite UNLOGGED BATCH a una tabla/partición o use llamadas separadas."),
                        SemanticError.Severity.WARNING));
            }
            if (pba.singlePartitionKnown) {
                out.add(new SemanticError(
                        "CAS-BATCH-003",
                        msg("UNLOGGED BATCH for a single partition — OK",
                                "UNLOGGED BATCH para una sola partición — OK"),
                        msg("This matches Cassandra guidance for same-partition throughput.",
                            "Encaja con la guía de Cassandra para rendimiento en la misma partición."),
                        SemanticError.Severity.INFO));
            }
        }

        if (isDefaultOrLoggedBatch(tokens) && !isBeginCounterBatch(tokens) && pba.singlePartitionKnown) {
            out.add(new SemanticError(
                    "CAS-BATCH-004",
                    msg("LOGGED BATCH used only for a single partition — prefer UNLOGGED",
                            "BATCH LOGGED solo para una partición — use UNLOGGED"),
                    msg("Use BEGIN UNLOGGED BATCH when all mutations share one partition key.",
                            "Use BEGIN UNLOGGED BATCH cuando todas las mutaciones comparten una partición."),
                    SemanticError.Severity.WARNING));
        }

        if (isBeginCounterBatch(tokens)) {
            if (batchContainsNonCounterStatement(tokens)) {
                out.add(new SemanticError(
                        "CAS-BATCH-005",
                        msg("COUNTER BATCH contains non-counter statements",
                            "BATCH COUNTER contiene sentencias que no son de contador"),
                        msg("Use only counter column updates; move INSERT/DELETE or regular updates outside this batch.",
                                "Use solo actualizaciones de columnas contador; mueva INSERT/DELETE u otras fuera del batch."),
                        SemanticError.Severity.ERROR));
            }
        }

        if (isPerformanceBatchPattern(tokens)) {
            out.add(new SemanticError(
                    "CQL-BATCH-005",
                    msg("BATCH for performance on same partition is valid; use UNLOGGED for best results",
                            "BATCH por rendimiento en la misma partición es válido; use UNLOGGED para mejores resultados"),
                    msg("Prefer BEGIN UNLOGGED BATCH when all operations target the same partition.",
                            "Prefiera BEGIN UNLOGGED BATCH cuando todas las operaciones van a la misma partición."),
                    SemanticError.Severity.INFO));
        }

        int lwtCount = countLwtStatementsInBatch(tokens);
        if (lwtCount > 1) {
            out.add(new SemanticError(
                    "CAS-LWT-002",
                    msg("BATCH with multiple lightweight transactions is not atomic as one Paxos round",
                            "BATCH con varias transacciones ligeras no es atómico como una sola ronda Paxos"),
                    msg("Use at most one conditional statement per batch, or run LWTs outside BATCH.",
                            "Use como máximo una sentencia condicional por batch, o ejecute LWT fuera del BATCH."),
                    SemanticError.Severity.ERROR));
        }
    }

    /** Logged batch: {@code BEGIN BATCH} or {@code BEGIN LOGGED BATCH} (not UNLOGGED / COUNTER). */
    private static boolean isDefaultOrLoggedBatch(List<CqlToken> t) {
        if (isBeginUnloggedBatch(t) || isBeginCounterBatch(t)) {
            return false;
        }
        return isBeginBatchStatement(t);
    }

    private static final class PartitionBatchAnalysis {
        final boolean crossPartition;
        final boolean singlePartitionKnown;

        PartitionBatchAnalysis(boolean crossPartition, boolean singlePartitionKnown) {
            this.crossPartition = crossPartition;
            this.singlePartitionKnown = singlePartitionKnown;
        }
    }

    private static PartitionBatchAnalysis analyzePartitionKeysInBatch(List<CqlToken> t, SchemaRegistry registry) {
        List<Integer> dmlStarts = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth != 0) {
                continue;
            }
            if (isKw(t, i, "APPLY") && i + 1 < t.size() && isKw(t, i + 1, "BATCH")) {
                break;
            }
            if (isKw(t, i, "INSERT") || isKw(t, i, "UPDATE") || isKw(t, i, "DELETE")) {
                dmlStarts.add(i);
            }
        }
        if (dmlStarts.isEmpty()) {
            return new PartitionBatchAnalysis(false, false);
        }
        List<String> tables = new ArrayList<>();
        List<String> fps = new ArrayList<>();
        for (int k = 0; k < dmlStarts.size(); k++) {
            int start = dmlStarts.get(k);
            int end = (k + 1 < dmlStarts.size()) ? dmlStarts.get(k + 1) : indexOfApplyBatch(t);
            if (end < 0 || end <= start) {
                end = t.size();
            }
            List<CqlToken> slice = t.subList(start, end);
            String table = extractTableFromDmlTokens(slice);
            String tnorm = table != null && !table.isBlank() ? normalizeTableKey(table) : "";
            tables.add(tnorm);
            TableSchema ts = table != null ? registry.getTable(table) : null;
            fps.add(buildPartitionFingerprint(slice, ts));
        }
        Set<String> distinctTables = new HashSet<>(tables);
        distinctTables.remove("");
        boolean cross = false;
        if (distinctTables.size() > 1) {
            cross = true;
        } else {
            Set<String> distinctFp = new HashSet<>();
            for (String f : fps) {
                if (f != null) {
                    distinctFp.add(f);
                }
            }
            if (distinctFp.size() > 1) {
                cross = true;
            }
        }
        boolean singleKnown = !fps.isEmpty()
                && fps.stream().noneMatch(Objects::isNull)
                && distinctTables.size() == 1
                && new HashSet<>(fps).size() == 1;
        return new PartitionBatchAnalysis(cross, singleKnown);
    }

    private static int indexOfApplyBatch(List<CqlToken> t) {
        int depth = 0;
        for (int i = 0; i + 1 < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && isKw(t, i, "APPLY") && isKw(t, i + 1, "BATCH")) {
                return i;
            }
        }
        return -1;
    }

    private static String extractTableFromDmlTokens(List<CqlToken> slice) {
        if (slice.isEmpty()) {
            return null;
        }
        if (isKw(slice, 0, "INSERT")) {
            return extractInsertTable(slice);
        }
        if (isKw(slice, 0, "UPDATE")) {
            return extractUpdateDeleteTable(slice, "UPDATE");
        }
        if (isKw(slice, 0, "DELETE")) {
            return extractUpdateDeleteTable(slice, "DELETE");
        }
        return null;
    }

    /**
     * Stable fingerprint of partition key literals/bindings from WHERE (or INSERT column/value pairs when schema known).
     */
    private static String buildPartitionFingerprint(List<CqlToken> slice, TableSchema ts) {
        if (slice.isEmpty()) {
            return null;
        }
        if (isKw(slice, 0, "INSERT")) {
            return fingerprintInsertPartition(slice, ts);
        }
        if (isKw(slice, 0, "UPDATE") || isKw(slice, 0, "DELETE")) {
            return fingerprintWherePartition(slice, ts);
        }
        return null;
    }

    private static String fingerprintWherePartition(List<CqlToken> slice, TableSchema ts) {
        int w = indexOfKeyword(slice, "WHERE");
        if (w < 0) {
            return null;
        }
        if (ts != null && !ts.partitionKeys.isEmpty()) {
            Map<String, String> eq = extractTopLevelEqualities(slice, w + 1);
            List<String> parts = new ArrayList<>();
            for (String pk : ts.partitionKeys) {
                String v = eq.get(pk);
                if (v == null) {
                    return null;
                }
                parts.add(pk + "=" + v);
            }
            parts.sort(String::compareTo);
            return String.join("&", parts);
        }
        return rawWhereEqualityFingerprint(slice, w + 1);
    }

    private static String rawWhereEqualityFingerprint(List<CqlToken> slice, int from) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = from; i < slice.size(); i++) {
            CqlToken tok = slice.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth != 0) {
                continue;
            }
            if (tok.type() == CqlTokenType.SEMICOLON || tok.type() == CqlTokenType.EOF) {
                break;
            }
            if (isKw(slice, i, "IF")) {
                break;
            }
            sb.append(tok.type().name()).append(':').append(tok.value()).append('|');
        }
        String s = sb.toString();
        return s.isEmpty() ? null : s;
    }

    /** Equalities at depth 0: {@code col = value} (value side simplified). */
    private static Map<String, String> extractTopLevelEqualities(List<CqlToken> slice, int from) {
        Map<String, String> m = new HashMap<>();
        int depth = 0;
        int i = from;
        while (i < slice.size()) {
            CqlToken tok = slice.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && isKw(slice, i, "IF")) {
                break;
            }
            if (depth == 0 && isNameToken(tok) && i + 2 < slice.size()
                    && slice.get(i + 1).type() == CqlTokenType.OPERATOR
                    && "=".equals(slice.get(i + 1).value())) {
                String col = normalizeIdent(tok.value());
                String val = equalityRhsFingerprint(slice, i + 2);
                m.put(col, val);
                i = skipEqualityRhs(slice, i + 2);
                continue;
            }
            i++;
        }
        return m;
    }

    private static String equalityRhsFingerprint(List<CqlToken> slice, int rhsStart) {
        int j = rhsStart;
        if (j >= slice.size()) {
            return "?";
        }
        CqlToken t0 = slice.get(j);
        if (t0.type() == CqlTokenType.IDENTIFIER && "?".equals(t0.value())) {
            return "?";
        }
        if (t0.type() == CqlTokenType.NUMBER || t0.type() == CqlTokenType.STRING
                || t0.type() == CqlTokenType.UUID_LITERAL || t0.type() == CqlTokenType.BOOLEAN) {
            return t0.value();
        }
        if (t0.type() == CqlTokenType.KEYWORD) {
            return t0.value().toLowerCase(Locale.ROOT);
        }
        return "?";
    }

    private static int skipEqualityRhs(List<CqlToken> slice, int rhsStart) {
        int j = rhsStart;
        if (j >= slice.size()) {
            return j;
        }
        CqlToken t0 = slice.get(j);
        if (t0.type() == CqlTokenType.LEFT_PAREN) {
            int close = findMatchingParen(slice, j);
            return close >= 0 ? close + 1 : j + 1;
        }
        return j + 1;
    }

    private static String fingerprintInsertPartition(List<CqlToken> slice, TableSchema ts) {
        if (ts == null || ts.partitionKeys.isEmpty()) {
            return null;
        }
        int ins = indexOfKeyword(slice, "INSERT");
        if (ins < 0 || !keywordSequenceAt(slice, ins, "INSERT", "INTO")) {
            return null;
        }
        int j = skipQualifiedName(slice, ins + 2);
        if (j < 0) {
            return null;
        }
        if (j < slice.size() && slice.get(j).type() == CqlTokenType.LEFT_PAREN) {
            int closeNames = findMatchingParen(slice, j);
            if (closeNames < 0) {
                return null;
            }
            List<String> colNames = splitTopLevelCommaIdents(slice, j + 1, closeNames);
            int vals = indexOfKeywordFrom(slice, closeNames, "VALUES");
            if (vals < 0 || vals + 1 >= slice.size() || slice.get(vals + 1).type() != CqlTokenType.LEFT_PAREN) {
                return null;
            }
            int closeVals = findMatchingParen(slice, vals + 1);
            if (closeVals < 0) {
                return null;
            }
            List<String> valStrs = splitTopLevelCommaValueFingerprints(slice, vals + 2, closeVals);
            if (colNames.size() != valStrs.size()) {
                return null;
            }
            Map<String, String> colToVal = new HashMap<>();
            for (int k = 0; k < colNames.size(); k++) {
                colToVal.put(normalizeIdent(colNames.get(k)), valStrs.get(k));
            }
            List<String> parts = new ArrayList<>();
            for (String pk : ts.partitionKeys) {
                String v = colToVal.get(pk);
                if (v == null) {
                    return null;
                }
                parts.add(pk + "=" + v);
            }
            parts.sort(String::compareTo);
            return String.join("&", parts);
        }
        return null;
    }

    private static List<String> splitTopLevelCommaValueFingerprints(List<CqlToken> slice, int start, int end) {
        List<String> acc = new ArrayList<>();
        int depth = 0;
        int segStart = start;
        for (int i = start; i < end; i++) {
            CqlToken tok = slice.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && tok.type() == CqlTokenType.COMMA) {
                acc.add(valueFingerprint(slice, segStart, i));
                segStart = i + 1;
            }
        }
        acc.add(valueFingerprint(slice, segStart, end));
        return acc;
    }

    private static String valueFingerprint(List<CqlToken> slice, int start, int end) {
        if (start >= end) {
            return "?";
        }
        CqlToken t0 = slice.get(start);
        if (end - start == 1) {
            if (t0.type() == CqlTokenType.IDENTIFIER && "?".equals(t0.value())) {
                return "?";
            }
            if (t0.type() == CqlTokenType.NUMBER || t0.type() == CqlTokenType.STRING
                    || t0.type() == CqlTokenType.UUID_LITERAL || t0.type() == CqlTokenType.BOOLEAN) {
                return t0.value();
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            CqlToken tok = slice.get(i);
            sb.append(tok.type().name()).append(':').append(tok.value()).append('|');
        }
        return sb.toString();
    }

    private static int countLwtStatementsInBatch(List<CqlToken> t) {
        List<Integer> dmlStarts = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth != 0) {
                continue;
            }
            if (isKw(t, i, "APPLY") && i + 1 < t.size() && isKw(t, i + 1, "BATCH")) {
                break;
            }
            if (isKw(t, i, "INSERT") || isKw(t, i, "UPDATE") || isKw(t, i, "DELETE")) {
                dmlStarts.add(i);
            }
        }
        int c = 0;
        for (int k = 0; k < dmlStarts.size(); k++) {
            int start = dmlStarts.get(k);
            int end = (k + 1 < dmlStarts.size()) ? dmlStarts.get(k + 1) : indexOfApplyBatch(t);
            if (end < 0 || end <= start) {
                end = t.size();
            }
            List<CqlToken> slice = t.subList(start, end);
            if (sliceHasLwt(slice)) {
                c++;
            }
        }
        return c;
    }

    private static boolean sliceHasLwt(List<CqlToken> slice) {
        if (hasLwtIfNotExists(slice)) {
            return true;
        }
        int w = indexOfKeyword(slice, "WHERE");
        if (w >= 0) {
            int depth = 0;
            for (int i = w + 1; i < slice.size(); i++) {
                CqlToken tok = slice.get(i);
                if (tok.type() == CqlTokenType.LEFT_PAREN) {
                    depth++;
                } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                    depth = Math.max(0, depth - 1);
                }
                if (depth != 0) {
                    continue;
                }
                if (isKw(slice, i, "IF")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBeginUnloggedBatch(List<CqlToken> t) {
        int i = firstSignificant(t);
        return i >= 0 && keywordSequenceAt(t, i, "BEGIN", "UNLOGGED", "BATCH");
    }

    private static boolean isBeginCounterBatch(List<CqlToken> t) {
        int i = firstSignificant(t);
        return i >= 0 && keywordSequenceAt(t, i, "BEGIN", "COUNTER", "BATCH");
    }

    private static boolean endsWithApplyBatch(List<CqlToken> t) {
        int j = lastSignificantBeforeEof(t);
        if (j < 1) {
            return false;
        }
        return isKw(t, j, "BATCH") && isKw(t, j - 1, "APPLY");
    }

    private static int lastSignificantBeforeEof(List<CqlToken> t) {
        for (int i = t.size() - 2; i >= 0; i--) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.SEMICOLON) {
                continue;
            }
            if (tok.type() == CqlTokenType.EOF) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean batchContainsNonCounterStatement(List<CqlToken> t) {
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth != 0) {
                continue;
            }
            if (!isKw(t, i, "INSERT") && !isKw(t, i, "DELETE")) {
                continue;
            }
            return true;
        }
        for (int i = 0; i < t.size(); i++) {
            if (!isKw(t, i, "UPDATE")) {
                continue;
            }
            if (!counterLikeSlice(t, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Heuristic: counter update often uses {@code col = col + n} or {@code col = col - n}.
     */
    private static boolean counterLikeSlice(List<CqlToken> t, int updateIdx) {
        int j = updateIdx + 1;
        if (j >= t.size()) {
            return false;
        }
        j = skipQualifiedName(t, j);
        if (j >= t.size() || !isKw(t, j, "SET")) {
            return false;
        }
        j++;
        while (j < t.size()) {
            if (isKw(t, j, "WHERE")) {
                break;
            }
            if (t.get(j).type() == CqlTokenType.OPERATOR && "=".equals(t.get(j).value())) {
                int left = j - 1;
                int right = j + 1;
                if (left >= 0 && right < t.size()
                        && nameOrStar(t.get(left))
                        && nameOrStar(t.get(right))
                        && right + 1 < t.size()
                        && t.get(right + 1).type() == CqlTokenType.OPERATOR) {
                    String op = t.get(right + 1).value();
                    if ("+".equals(op) || "-".equals(op)) {
                        return true;
                    }
                }
            }
            j++;
        }
        return false;
    }

    private static boolean nameOrStar(CqlToken tok) {
        return tok.type() == CqlTokenType.IDENTIFIER
                || tok.type() == CqlTokenType.UUID_LITERAL
                || (tok.type() == CqlTokenType.ASTERISK && "*".equals(tok.value()));
    }

    private static Set<String> extractDmlTablesInBatch(List<CqlToken> t) {
        Set<String> out = new HashSet<>();
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth != 0) {
                continue;
            }
            if (isKw(t, i, "INSERT") && i + 1 < t.size() && isKw(t, i + 1, "INTO")) {
                int j = i + 2;
                j = skipQualifiedName(t, j);
                if (j >= 0) {
                    String name = qualifiedNameBefore(t, j);
                    if (name != null) {
                        out.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            } else if (isKw(t, i, "UPDATE")) {
                int j = skipQualifiedName(t, i + 1);
                if (j >= 0) {
                    String name = qualifiedNameBefore(t, j);
                    if (name != null) {
                        out.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            } else if (isKw(t, i, "DELETE") && isKw(t, i + 1, "FROM")) {
                int j = skipQualifiedName(t, i + 2);
                if (j >= 0) {
                    String name = qualifiedNameBefore(t, j);
                    if (name != null) {
                        out.add(name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return out;
    }

    private static String qualifiedNameBefore(List<CqlToken> t, int afterIdx) {
        int end = afterIdx - 1;
        if (end < 0) {
            return null;
        }
        int start = end;
        while (start - 2 >= 0 && t.get(start - 1).type() == CqlTokenType.DOT) {
            start -= 2;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = start; k <= end; k++) {
            if (k > start) {
                sb.append('.');
            }
            sb.append(t.get(k).value());
        }
        return sb.toString();
    }

    private static int skipQualifiedName(List<CqlToken> t, int j) {
        if (j < 0 || j >= t.size() || !isNameToken(t.get(j))) {
            return -1;
        }
        j++;
        while (j < t.size() && t.get(j).type() == CqlTokenType.DOT) {
            j++;
            if (j >= t.size() || !isNameToken(t.get(j))) {
                return -1;
            }
            j++;
        }
        return j;
    }

    private static int estimateBatchStatementCount(List<CqlToken> t) {
        int n = 0;
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && (isKw(t, i, "INSERT") || isKw(t, i, "UPDATE") || isKw(t, i, "DELETE"))) {
                n++;
            }
        }
        return n;
    }

    private static boolean isPerformanceBatchPattern(List<CqlToken> t) {
        int inserts = 0;
        String lastTable = null;
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth != 0 || !isKw(t, i, "INSERT")) {
                continue;
            }
            if (i + 1 < t.size() && isKw(t, i + 1, "INTO")) {
                int j = skipQualifiedName(t, i + 2);
                if (j >= 0) {
                    String tbl = qualifiedNameBefore(t, j);
                    if (tbl != null) {
                        inserts++;
                        lastTable = tbl.toLowerCase(Locale.ROOT);
                    }
                }
            }
        }
        if (inserts < 2 || lastTable == null) {
            return false;
        }
        for (int i = 0; i < t.size(); i++) {
            if (isKw(t, i, "INSERT") && i + 1 < t.size() && isKw(t, i + 1, "INTO")) {
                int j = skipQualifiedName(t, i + 2);
                if (j >= 0) {
                    String tbl = qualifiedNameBefore(t, j);
                    if (tbl != null && !lastTable.equals(tbl.toLowerCase(Locale.ROOT))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int firstSignificant(List<CqlToken> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            CqlToken t = tokens.get(i);
            if (t.type() == CqlTokenType.EOF) {
                return i;
            }
            if (t.type() != CqlTokenType.SEMICOLON) {
                return i;
            }
        }
        return -1;
    }

    private void analyzeSelect(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        if (indexOfKeyword(tokens, "FROM") < 0) {
            out.add(new SemanticError(
                    "CQL-STRUCT-SELECT",
                    msg("SELECT must include a FROM clause", "SELECT debe incluir FROM"),
                    msg("Use SELECT … FROM keyspace.table (or table) …",
                            "Use SELECT … FROM keyspace.tabla (o tabla) …"),
                    SemanticError.Severity.ERROR));
        }
    }

    private void analyzeInsert(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        int ins = indexOfKeyword(tokens, "INSERT");
        if (ins < 0 || !keywordSequenceAt(tokens, ins, "INSERT", "INTO")) {
            out.add(new SemanticError(
                    "CQL-STRUCT-INSERT",
                    msg("INSERT must use INSERT INTO …", "INSERT debe usar INSERT INTO …"),
                    msg("Syntax: INSERT INTO table [(columns)] VALUES (…) [USING TTL …]",
                            "Sintaxis: INSERT INTO tabla [(columnas)] VALUES (…) [USING TTL …]"),
                    SemanticError.Severity.ERROR));
            return;
        }
        if (indexOfKeywordFrom(tokens, ins, "VALUES") < 0) {
            out.add(new SemanticError(
                    "CQL-STRUCT-INSERT-VALUES",
                    msg("INSERT should specify VALUES (…) for row data",
                            "INSERT debe especificar VALUES (…) para los datos de fila"),
                    msg("Provide a VALUES clause or use INSERT JSON if your driver supports it.",
                            "Proporcione VALUES o use INSERT JSON si su driver lo admite."),
                    SemanticError.Severity.ERROR));
        }
        if (hasLwtIfNotExists(tokens)) {
            if (isHotPartitionLwt(tokens, "INSERT", registry)) {
                out.add(new SemanticError(
                        "CAS-LWT-001",
                        msg("LWT on a high-cardinality / hot partition key — consensus path is slow",
                                "LWT en partición caliente / alta cardinalidad — el consenso es lento"),
                        msg("LWT requires Paxos rounds; avoid hot keys or batch contention on the same partition.",
                                "LWT usa rondas Paxos; evite claves calientes o contención en la misma partición."),
                        SemanticError.Severity.WARNING));
            }
            out.add(new SemanticError(
                    "CAS-LWT-003",
                    msg("IF NOT EXISTS — verify the driver result (applied flag) in application code",
                            "IF NOT EXISTS — verifique en la aplicación el resultado (flag applied) del driver"),
                    msg("Check ResultSet.wasApplied() or equivalent before assuming the row was inserted.",
                            "Revise wasApplied() o equivalente antes de asumir que la fila se insertó."),
                    SemanticError.Severity.INFO));
        }
        analyzeLwtIfClause(tokens, "INSERT", out, registry);
    }

    private void analyzeUpdate(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        int upd = indexOfKeyword(tokens, "UPDATE");
        if (upd < 0) {
            return;
        }
        if (indexOfKeywordFrom(tokens, upd + 1, "SET") < 0) {
            out.add(new SemanticError(
                    "CQL-STRUCT-UPDATE",
                    msg("UPDATE must include a SET clause", "UPDATE debe incluir SET"),
                    msg("Syntax: UPDATE table USING … SET col = val [, …] WHERE …",
                            "Sintaxis: UPDATE tabla USING … SET col = val [, …] WHERE …"),
                    SemanticError.Severity.ERROR));
        }
        if (!hasWhereAtDepthZero(tokens)) {
            out.add(new SemanticError(
                    "CQL-NOWHERE-001",
                    msg("UPDATE without WHERE in CQL affects all rows",
                            "UPDATE sin WHERE en CQL afecta todas las filas"),
                    msg("Add WHERE partition_key = ? to target specific rows",
                            "Añada WHERE clave_particion = ? para filas concretas"),
                    SemanticError.Severity.ERROR));
        }
        checkCollectionUpdateSemantics(tokens, out);
        checkListIndexNonNegative(tokens, out);
        if (findConditionalIfIndex(tokens) >= 0 || hasLwtIfExpressionAfterWhere(tokens)) {
            if (isHotPartitionLwt(tokens, "UPDATE", registry)) {
                out.add(new SemanticError(
                        "CAS-LWT-001",
                        msg("LWT on a high-cardinality / hot partition key — consensus path is slow",
                                "LWT en partición caliente / alta cardinalidad — el consenso es lento"),
                        msg("LWT requires Paxos rounds; avoid hot keys or batch contention on the same partition.",
                                "LWT usa rondas Paxos; evite claves calientes o contención en la misma partición."),
                        SemanticError.Severity.WARNING));
            }
            out.add(new SemanticError(
                    "CAS-LWT-003",
                    msg("UPDATE … IF — verify the driver result (applied flag) in application code",
                            "UPDATE … IF — verifique en la aplicación el resultado (flag applied) del driver"),
                    msg("Check wasApplied() / CAS result before assuming the conditional update ran.",
                            "Revise wasApplied() / resultado CAS antes de asumir que el UPDATE condicional se aplicó."),
                    SemanticError.Severity.INFO));
        }
        analyzeLwtIfClause(tokens, "UPDATE", out, registry);
    }

    private void analyzeDelete(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        int fromKw = indexOfKeyword(tokens, "FROM");
        if (fromKw < 0) {
            out.add(new SemanticError(
                    "CQL-STRUCT-DELETE",
                    msg("DELETE requires a FROM clause", "DELETE requiere FROM"),
                    msg("Syntax: DELETE [columns] FROM table WHERE …",
                            "Sintaxis: DELETE [columnas] FROM tabla WHERE …"),
                    SemanticError.Severity.ERROR));
        }
        if (!hasWhereAtDepthZero(tokens)) {
            out.add(new SemanticError(
                    "CQL-NOWHERE-002",
                    msg("DELETE without WHERE in CQL affects all rows",
                            "DELETE sin WHERE en CQL afecta todas las filas"),
                    msg("Add WHERE partition_key = ? to target specific rows",
                            "Añada WHERE clave_particion = ? para filas concretas"),
                    SemanticError.Severity.ERROR));
        }
        checkDeleteMapCellOnPk(tokens, out, registry);
        if (hasLwtIfExistsAfterWhere(tokens) || hasLwtIfExpressionAfterWhere(tokens)) {
            if (isHotPartitionLwt(tokens, "DELETE", registry)) {
                out.add(new SemanticError(
                        "CAS-LWT-001",
                        msg("LWT on a high-cardinality / hot partition key — consensus path is slow",
                                "LWT en partición caliente / alta cardinalidad — el consenso es lento"),
                        msg("LWT requires Paxos rounds; avoid hot keys or batch contention on the same partition.",
                                "LWT usa rondas Paxos; evite claves calientes o contención en la misma partición."),
                        SemanticError.Severity.WARNING));
            }
            out.add(new SemanticError(
                    "CAS-LWT-003",
                    msg("DELETE IF / IF EXISTS — verify the driver result (applied flag) in application code",
                            "DELETE IF / IF EXISTS — verifique en la aplicación el resultado (flag applied) del driver"),
                    msg("Check wasApplied() or equivalent before assuming the row was deleted.",
                            "Revise wasApplied() o equivalente antes de asumir que la fila se borró."),
                    SemanticError.Severity.INFO));
        }
        analyzeLwtIfClause(tokens, "DELETE", out, registry);
    }

    private void analyzeCreate(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        int c = indexOfKeyword(tokens, "CREATE");
        if (c < 0) {
            return;
        }
        if (keywordSequenceAt(tokens, c, "CREATE", "TABLE")) {
            analyzeCreateTable(tokens, out);
            checkCollectionTypeFrozen(tokens, out);
            return;
        }
        if (keywordSequenceAt(tokens, c, "CREATE", "MATERIALIZED", "VIEW")) {
            analyzeCreateMaterializedView(tokens, out, registry);
            return;
        }
        if (keywordSequenceAt(tokens, c, "CREATE", "INDEX")
                || keywordSequenceAt(tokens, c, "CREATE", "CUSTOM", "INDEX")) {
            analyzeCreateIndex(tokens, out, registry);
            return;
        }
        if (keywordSequenceAt(tokens, c, "CREATE", "TRIGGER")) {
            analyzeCreateTrigger(tokens, out);
            return;
        }
        if (keywordSequenceAt(tokens, c, "CREATE", "FUNCTION")
                || keywordSequenceAt(tokens, c, "CREATE", "OR", "REPLACE", "FUNCTION")) {
            analyzeCreateFunction(tokens, out);
            return;
        }
        if (keywordSequenceAt(tokens, c, "CREATE", "AGGREGATE")
                || keywordSequenceAt(tokens, c, "CREATE", "OR", "REPLACE", "AGGREGATE")) {
            analyzeCreateAggregate(tokens, out, registry);
            return;
        }
        if (keywordSequenceAt(tokens, c, "CREATE", "ROLE")
                || keywordSequenceAt(tokens, c, "CREATE", "OR", "REPLACE", "ROLE")) {
            analyzeDclCreateRole(tokens, out);
            return;
        }
        if (keywordSequenceAt(tokens, c, "CREATE", "USER")) {
            analyzeDclCreateUser(tokens, out);
            return;
        }
    }

    private void analyzeAlter(List<CqlToken> tokens, List<SemanticError> out) {
        int a = indexOfKeyword(tokens, "ALTER");
        if (a < 0) {
            return;
        }
        if (keywordSequenceAt(tokens, a, "ALTER", "ROLE")) {
            analyzeDclAlterRole(tokens, out);
            return;
        }
        if (keywordSequenceAt(tokens, a, "ALTER", "USER")) {
            analyzeDclAlterUser(tokens, out);
            return;
        }
        if (!keywordSequenceAt(tokens, a, "ALTER", "TYPE")) {
            return;
        }
        for (int i = a; i + 1 < tokens.size(); i++) {
            if (isKw(tokens, i, "ADD")) {
                return;
            }
            if (isKw(tokens, i, "RENAME")) {
                out.add(new SemanticError(
                        "CQL-UDT-002",
                        msg("Cassandra only supports adding fields to UDT; renaming or dropping fields requires table recreation",
                                "Cassandra solo permite añadir campos a UDT; renombrar o eliminar requiere recrear tablas"),
                        msg("Create a new UDT and migrate data, or recreate dependent tables.",
                                "Cree un UDT nuevo y migre datos, o recree las tablas dependientes."),
                        SemanticError.Severity.ERROR));
                return;
            }
            if (isKw(tokens, i, "DROP")) {
                out.add(new SemanticError(
                        "CQL-UDT-002",
                        msg("Cassandra only supports adding fields to UDT; renaming or dropping fields requires table recreation",
                                "Cassandra solo permite añadir campos a UDT; renombrar o eliminar requiere recrear tablas"),
                        msg("Create a new UDT and migrate data, or recreate dependent tables.",
                                "Cree un UDT nuevo y migre datos, o recree las tablas dependientes."),
                        SemanticError.Severity.ERROR));
                return;
            }
        }
    }

    private void analyzeCreateTable(List<CqlToken> tokens, List<SemanticError> out) {
        int c = indexOfKeyword(tokens, "CREATE");
        if (c < 0 || !keywordSequenceAt(tokens, c, "CREATE", "TABLE")) {
            return;
        }
        int j = c + 2;
        j = skipIfNotExistsClause(tokens, j);
        j = consumeQualifiedTableName(tokens, j);
        if (j < 0) {
            out.add(new SemanticError(
                    "CQL-STRUCT-CREATE-TABLE",
                    msg("CREATE TABLE requires a table name", "CREATE TABLE requiere un nombre de tabla"),
                    msg("Syntax: CREATE TABLE [IF NOT EXISTS] keyspace.table ( … )",
                            "Sintaxis: CREATE TABLE [IF NOT EXISTS] keyspace.tabla ( … )"),
                    SemanticError.Severity.ERROR));
            return;
        }
        if (j >= tokens.size() || tokens.get(j).type() != CqlTokenType.LEFT_PAREN) {
            out.add(new SemanticError(
                    "CQL-STRUCT-CREATE-TABLE",
                    msg("CREATE TABLE requires a column definition list in parentheses",
                            "CREATE TABLE requiere lista de definiciones de columna entre paréntesis"),
                    msg("Add ( column definitions ) after the table name.",
                            "Añada ( definiciones de columnas ) tras el nombre de tabla."),
                    SemanticError.Severity.ERROR));
        }
    }

    private void analyzeCreateMaterializedView(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        String base = extractFromTableAfterAs(tokens);
        TableSchema baseSchema = base != null ? registry.getTable(base) : null;
        Set<String> mvPk = extractPrimaryKeyColumns(tokens);
        if (baseSchema != null && !baseSchema.allPkColumns().isEmpty()) {
            for (String need : baseSchema.allPkColumns()) {
                if (!mvPk.contains(normalizeIdent(need))) {
                    out.add(new SemanticError(
                            "CQL-MV-001",
                            msg("Materialized view must include all primary key columns of base table",
                                    "La vista materializada debe incluir todas las columnas PK de la tabla base"),
                            msg("PRIMARY KEY of the view must contain every partition and clustering column of the base table.",
                                    "La PRIMARY KEY de la vista debe contener todas las columnas de partición y clustering de la base."),
                            SemanticError.Severity.ERROR));
                    break;
                }
            }
        }
        if (baseSchema != null && !baseSchema.allPkColumns().isEmpty()) {
            String where = extractWhereClauseText(tokens).toLowerCase(Locale.ROOT);
            for (String pkCol : baseSchema.allPkColumns()) {
                String n = normalizeIdent(pkCol);
                if (!where.contains(n + " is not null")) {
                    out.add(new SemanticError(
                            "CQL-MV-002",
                            msg("Materialized view WHERE clause must include IS NOT NULL for all primary key columns",
                                    "El WHERE de la vista materializada debe incluir IS NOT NULL para todas las columnas PK"),
                            msg("Add `AND " + pkCol + " IS NOT NULL` (and similar for each PK column) in the WHERE clause.",
                                    "Añada `AND " + pkCol + " IS NOT NULL` (y similar para cada columna PK) en WHERE."),
                            SemanticError.Severity.ERROR));
                    break;
                }
            }
        } else if (!extractWhereClauseText(tokens).toLowerCase(Locale.ROOT).contains("is not null")) {
            out.add(new SemanticError(
                    "CQL-MV-002",
                    msg("Materialized view WHERE clause must include IS NOT NULL for all primary key columns",
                            "El WHERE de la vista materializada debe incluir IS NOT NULL para todas las columnas PK"),
                    msg("Include `IS NOT NULL` guards for every partition/clustering column of the base table.",
                            "Incluya `IS NOT NULL` para cada columna de partición/clustering de la tabla base."),
                    SemanticError.Severity.ERROR));
        }
        if (base != null && registry.getMvCount(base) > 3) {
            out.add(new SemanticError(
                    "CQL-MV-003",
                    msg("Many materialized views increase write overhead; consider denormalizing instead",
                            "Muchas vistas materializan incrementan la escritura; considere desnormalizar"),
                    msg("Reduce the number of views per base table or duplicate data explicitly.",
                            "Reduzca vistas por tabla base o duplique datos explícitamente."),
                    SemanticError.Severity.WARNING));
        }
    }

    private static String extractFromTableAfterAs(List<CqlToken> t) {
        int asIdx = -1;
        int depth = 0;
        for (int i = 0; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && isKw(t, i, "AS")) {
                asIdx = i;
                break;
            }
        }
        if (asIdx < 0) {
            return null;
        }
        int from = indexOfKeywordFrom(t, asIdx + 1, "FROM");
        if (from < 0) {
            return null;
        }
        int j = skipQualifiedName(t, from + 1);
        if (j < 0) {
            return null;
        }
        return qualifiedNameBefore(t, j);
    }

    private static Set<String> extractPrimaryKeyColumns(List<CqlToken> t) {
        int pk = -1;
        int depth = 0;
        for (int i = 0; i + 1 < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && isKw(t, i, "PRIMARY") && isKw(t, i + 1, "KEY")) {
                pk = i + 2;
                break;
            }
        }
        Set<String> cols = new HashSet<>();
        if (pk < 0 || pk >= t.size() || t.get(pk).type() != CqlTokenType.LEFT_PAREN) {
            return cols;
        }
        int close = findMatchingParen(t, pk);
        if (close < 0) {
            return cols;
        }
        int inner = pk + 1;
        if (inner < close && t.get(inner).type() == CqlTokenType.LEFT_PAREN) {
            int innerClose = findMatchingParen(t, inner);
            if (innerClose > 0) {
                for (String c : splitTopLevelCommaIdents(t, inner + 1, innerClose)) {
                    cols.add(normalizeIdent(c));
                }
                for (String c : splitTopLevelCommaIdents(t, innerClose + 1, close)) {
                    cols.add(normalizeIdent(c));
                }
            }
        } else {
            List<String> parts = splitTopLevelCommaIdents(t, inner, close);
            if (!parts.isEmpty()) {
                cols.add(normalizeIdent(parts.get(0)));
                for (int k = 1; k < parts.size(); k++) {
                    cols.add(normalizeIdent(parts.get(k)));
                }
            }
        }
        return cols;
    }

    private static List<String> splitTopLevelCommaIdents(List<CqlToken> t, int start, int end) {
        List<String> acc = new ArrayList<>();
        int depth = 0;
        int segStart = start;
        for (int i = start; i < end; i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && tok.type() == CqlTokenType.COMMA) {
                addIfIdent(t, segStart, i, acc);
                segStart = i + 1;
            }
        }
        addIfIdent(t, segStart, end, acc);
        return acc;
    }

    private static void addIfIdent(List<CqlToken> t, int start, int end, List<String> acc) {
        if (start >= end) {
            return;
        }
        if (end - start == 1 && isNameToken(t.get(start))) {
            acc.add(t.get(start).value());
        }
    }

    private static int findMatchingParen(List<CqlToken> t, int openIdx) {
        if (openIdx < 0 || t.get(openIdx).type() != CqlTokenType.LEFT_PAREN) {
            return -1;
        }
        int depth = 0;
        for (int i = openIdx; i < t.size(); i++) {
            if (t.get(i).type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (t.get(i).type() == CqlTokenType.RIGHT_PAREN) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String extractWhereClauseText(List<CqlToken> t) {
        int w = indexOfKeyword(t, "WHERE");
        if (w < 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = w; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.EOF || tok.type() == CqlTokenType.SEMICOLON) {
                break;
            }
            if (isKw(t, i, "PRIMARY")) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(tok.value());
        }
        return sb.toString();
    }

    private void analyzeCreateIndex(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        int c = indexOfKeyword(tokens, "CREATE");
        if (c < 0) {
            return;
        }
        int j = c + 1;
        if (keywordSequenceAt(tokens, c, "CREATE", "CUSTOM", "INDEX")) {
            j = c + 3;
        } else if (keywordSequenceAt(tokens, c, "CREATE", "INDEX")) {
            j = c + 2;
        } else {
            return;
        }
        j = skipIfNotExistsClause(tokens, j);
        boolean unnamed = j < tokens.size() && isKw(tokens, j, "ON");
        if (unnamed) {
            out.add(new SemanticError(
                    "CQL-IDX-002",
                    msg("Unnamed indexes get auto-generated names; consider naming them for easier management",
                            "Índices sin nombre reciben nombres autogenerados; considere nombrarlos"),
                    msg("Prefer CREATE INDEX meaningful_name ON … for Cassandra 3+ clarity.",
                            "Prefiera CREATE INDEX nombre_significativo ON … para claridad en Cassandra 3+."),
                    SemanticError.Severity.INFO));
        }
        int on = indexOfKeyword(tokens, "ON");
        if (on > 0) {
            int jq = skipQualifiedName(tokens, on + 1);
            if (jq > 0) {
                String table = qualifiedNameBefore(tokens, jq);
                String col = findIndexColumnName(tokens, on);
                if (table != null && col != null) {
                    TableSchema ts = registry.getTable(table);
                    if (ts != null && ts.isPartitionKey(normalizeIdent(col))) {
                        out.add(new SemanticError(
                                "CQL-IDX-001",
                                msg("Indexing partition key columns is redundant; they are already indexed",
                                        "Indexar columnas de clave de partición es redundante; ya están indexadas"),
                                msg("Drop the secondary index on the partition key.",
                                        "Elimine el índice secundario sobre la clave de partición."),
                                SemanticError.Severity.WARNING));
                    }
                    if (highCardinalityHint(col)) {
                        out.add(new SemanticError(
                                "CQL-IDX-003",
                                msg("Secondary indexes on high-cardinality columns are inefficient in Cassandra; consider denormalization",
                                        "Índices secundarios en columnas de alta cardinalidad son ineficientes; considere desnormalizar"),
                                msg("Use query tables keyed by that column or distribute hot partitions.",
                                        "Use tablas de consulta por esa columna o distribuya particiones calientes."),
                                SemanticError.Severity.WARNING));
                    }
                }
            }
        }
        if (indexOfKeyword(tokens, "CUSTOM") >= 0) {
            boolean hasUsingClass = false;
            for (int i = 0; i + 2 < tokens.size(); i++) {
                if (isKw(tokens, i, "USING") && tokens.get(i + 1).type() == CqlTokenType.STRING) {
                    hasUsingClass = true;
                    break;
                }
            }
            if (!hasUsingClass) {
                out.add(new SemanticError(
                        "CQL-IDX-004",
                        msg("CREATE CUSTOM INDEX normally requires USING 'class.name'",
                                "CREATE CUSTOM INDEX normalmente requiere USING 'class.name'"),
                        msg("Specify the SSTable-attached secondary index implementation class.",
                                "Especifique la clase de índice secundario ligado a SSTable."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private static boolean highCardinalityHint(String col) {
        String n = col.toLowerCase(Locale.ROOT);
        return n.equals("id") || n.endsWith("_id") || n.contains("uuid") || n.equals("email") || n.equals("username");
    }

    private static String findIndexColumnName(List<CqlToken> t, int onIdx) {
        int lp = -1;
        for (int i = onIdx + 1; i < t.size(); i++) {
            if (t.get(i).type() == CqlTokenType.LEFT_PAREN) {
                lp = i;
                break;
            }
        }
        if (lp < 0) {
            return null;
        }
        int rp = findMatchingParen(t, lp);
        if (rp < 0) {
            return null;
        }
        for (int i = lp + 1; i < rp; i++) {
            if (isNameToken(t.get(i))) {
                return t.get(i).value();
            }
        }
        return null;
    }

    private void checkCollectionUpdateSemantics(List<CqlToken> t, List<SemanticError> out) {
        for (int i = 0; i + 2 < t.size(); i++) {
            if (t.get(i).type() != CqlTokenType.OPERATOR) {
                continue;
            }
            String op = t.get(i).value();
            if (!"+=".equals(op) && !"+".equals(op)) {
                continue;
            }
            if (t.get(i + 1).type() == CqlTokenType.LEFT_BRACKET
                    && t.get(i + 2).type() == CqlTokenType.RIGHT_BRACKET) {
                out.add(new SemanticError(
                        "CQL-COL-001",
                        msg("Appending empty list has no effect", "Añadir una lista vacía no tiene efecto"),
                        msg("Remove the no-op or supply list elements.",
                                "Elimine la operación vacía o aporte elementos a la lista."),
                        SemanticError.Severity.WARNING));
            }
            if (t.get(i + 1).type() == CqlTokenType.LEFT_BRACE
                    && t.get(i + 2).type() == CqlTokenType.RIGHT_BRACE) {
                out.add(new SemanticError(
                        "CQL-COL-002",
                        msg("Merging empty map has no effect", "Fusionar un mapa vacío no tiene efecto"),
                        msg("Remove the no-op or supply map entries.",
                                "Elimine la operación vacía o aporte entradas al mapa."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private void checkListIndexNonNegative(List<CqlToken> t, List<SemanticError> out) {
        for (int i = 0; i + 3 < t.size(); i++) {
            if (t.get(i).type() != CqlTokenType.OPERATOR || !"=".equals(t.get(i).value())) {
                continue;
            }
            if (t.get(i - 1).type() != CqlTokenType.RIGHT_BRACKET) {
                continue;
            }
            int rb = i - 1;
            int lb = -1;
            int depthBracket = 0;
            for (int k = rb - 1; k >= 0; k--) {
                if (t.get(k).type() == CqlTokenType.RIGHT_BRACKET) {
                    depthBracket++;
                } else if (t.get(k).type() == CqlTokenType.LEFT_BRACKET) {
                    if (depthBracket == 0) {
                        lb = k;
                        break;
                    }
                    depthBracket--;
                }
            }
            if (lb < 0 || rb - lb < 2) {
                continue;
            }
            if (t.get(lb + 1).type() == CqlTokenType.OPERATOR && "-".equals(t.get(lb + 1).value())) {
                out.add(new SemanticError(
                        "CQL-COL-004",
                        msg("List index must be non-negative", "El índice de lista debe ser no negativo"),
                        msg("Use a valid list index (>= 0) in the bracket expression.",
                                "Use un índice de lista válido (>= 0) entre corchetes."),
                        SemanticError.Severity.ERROR));
            } else if (t.get(lb + 1).type() == CqlTokenType.NUMBER && t.get(lb + 1).value().startsWith("-")) {
                out.add(new SemanticError(
                        "CQL-COL-004",
                        msg("List index must be non-negative", "El índice de lista debe ser no negativo"),
                        msg("Use a valid list index (>= 0) in the bracket expression.",
                                "Use un índice de lista válido (>= 0) entre corchetes."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private void checkDeleteMapCellOnPk(List<CqlToken> t, List<SemanticError> out, SchemaRegistry registry) {
        int del = indexOfKeyword(t, "DELETE");
        if (del < 0) {
            return;
        }
        int from = indexOfKeyword(t, "FROM");
        if (from < 0) {
            return;
        }
        String table = null;
        int j = skipQualifiedName(t, from + 1);
        if (j >= 0) {
            table = qualifiedNameBefore(t, j);
        }
        TableSchema ts = table != null ? registry.getTable(table) : null;
        for (int i = del + 1; i < from; i++) {
            if (t.get(i).type() != CqlTokenType.LEFT_BRACKET || i == 0) {
                continue;
            }
            if (!isNameToken(t.get(i - 1))) {
                continue;
            }
            String mapCol = t.get(i - 1).value();
            int rb = matchingBracket(t, i, CqlTokenType.LEFT_BRACKET, CqlTokenType.RIGHT_BRACKET);
            if (rb < 0 || rb - i < 2) {
                continue;
            }
            String key = null;
            if (isNameToken(t.get(i + 1))) {
                key = t.get(i + 1).value();
            }
            if (ts != null && key != null && ts.isAnyPk(normalizeIdent(key))) {
                out.add(new SemanticError(
                        "CQL-COL-003",
                        msg("Cannot delete individual cells of primary key columns",
                                "No puede borrar celdas individuales de columnas de clave primaria"),
                        msg("You cannot sub-delete a map entry when the key refers to a primary key column.",
                                "No puede eliminar una entrada de mapa cuando la clave es columna PK."),
                        SemanticError.Severity.ERROR));
                return;
            }
        }
    }

    private static int matchingBracket(List<CqlToken> t, int open, CqlTokenType left, CqlTokenType right) {
        int depth = 0;
        for (int i = open; i < t.size(); i++) {
            if (t.get(i).type() == left) {
                depth++;
            } else if (t.get(i).type() == right) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void checkCollectionTypeFrozen(List<CqlToken> t, List<SemanticError> out) {
        for (int i = 0; i + 2 < t.size(); i++) {
            if (!isKw(t, i, "LIST") && !isKw(t, i, "SET") && !isKw(t, i, "MAP")) {
                continue;
            }
            if (t.get(i + 1).type() != CqlTokenType.LESS_THAN) {
                continue;
            }
            if (isKw(t, i, "MAP")) {
                continue;
            }
            int inner = i + 2;
            if (inner < t.size() && isKw(t, inner, "FROZEN")) {
                continue;
            }
            if (inner >= t.size() || !isNameToken(t.get(inner))) {
                continue;
            }
            String typeName = t.get(inner).value().toLowerCase(Locale.ROOT);
            if (PRIMITIVE_TYPES.contains(typeName)) {
                continue;
            }
            out.add(new SemanticError(
                    "CQL-UDT-001",
                    msg("UDTs inside collections must be wrapped with FROZEN<TypeName>",
                            "Los UDT dentro de colecciones deben ir con FROZEN<Tipo>"),
                    msg("Use frozen<" + t.get(inner).value() + "> (or nested frozen) inside the collection type.",
                            "Use frozen<" + t.get(inner).value() + "> (o frozen anidado) dentro del tipo de colección."),
                    SemanticError.Severity.ERROR));
        }
    }

    private void applyGlobalRules(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        if (containsJoinWord(tokens)) {
            out.add(new SemanticError(
                    "CQL-JOIN-001",
                    msg("Cassandra does not support JOINs", "Cassandra no admite JOIN"),
                    msg("Denormalize your data model or use separate queries with client-side joins",
                            "Desnormalice el modelo o use consultas separadas con joins en el cliente"),
                    SemanticError.Severity.ERROR));
        }
        if (containsAllowFiltering(tokens)) {
            out.add(new SemanticError(
                    "CQL-FILTER-001",
                    msg("ALLOW FILTERING causes full table scan",
                            "ALLOW FILTERING provoca escaneo completo de tabla"),
                    msg("Add proper partition key to WHERE clause to avoid full cluster scan",
                            "Añada la clave de partición en WHERE para evitar escaneo del clúster"),
                    SemanticError.Severity.WARNING));
        }
        if (containsOrderBy(tokens)) {
            out.add(new SemanticError(
                    "CQL-ORDERBY-001",
                    msg("ORDER BY in Cassandra only works on clustering columns defined in the table schema",
                            "ORDER BY en Cassandra solo funciona sobre columnas de clustering del esquema"),
                    msg("Ensure the ORDER BY column is a clustering column; otherwise remove it",
                            "Asegúrese de que ORDER BY sea columna de clustering; si no, elimínelo"),
                    SemanticError.Severity.WARNING));
        }
        checkTtlValues(tokens, out);
        checkLowTtl(tokens, out);
        checkWritetimeUsage(tokens, out, registry);
    }

    private void checkTtlValues(List<CqlToken> t, List<SemanticError> out) {
        for (int i = 0; i + 2 < t.size(); i++) {
            if (!isKw(t, i, "USING") || !isKw(t, i + 1, "TTL")) {
                continue;
            }
            CqlToken num = t.get(i + 2);
            if (num.type() != CqlTokenType.NUMBER) {
                continue;
            }
            try {
                long v = parseLongNumber(num.value());
                if (v == 0) {
                    out.add(new SemanticError(
                            "CQL-TTL-002",
                            msg("TTL of 0 removes the TTL (makes data permanent); use DELETE if you want to remove data",
                                    "TTL 0 quita el TTL (datos permanentes); use DELETE para borrar filas"),
                            msg("Omit USING TTL or use DELETE to remove the row.",
                                    "Omita USING TTL o use DELETE para eliminar la fila."),
                            SemanticError.Severity.ERROR));
                } else if (v < 0) {
                    out.add(new SemanticError(
                            "CQL-TTL-003",
                            msg("TTL must be a positive integer (seconds)",
                                    "TTL debe ser un entero positivo (segundos)"),
                            msg("Provide a positive TTL in seconds.",
                                    "Indique un TTL positivo en segundos."),
                            SemanticError.Severity.ERROR));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void checkWritetimeUsage(List<CqlToken> t, List<SemanticError> out, SchemaRegistry registry) {
        String fromTable = extractSimpleFromTable(t);
        TableSchema ts = fromTable != null ? registry.getTable(fromTable) : null;
        checkNativeAggregateOnColumn(t, out, ts, "WRITETIME",
                "CQL-WT-002",
                msg("WRITETIME requires a specific column name, not *",
                        "WRITETIME requiere un nombre de columna concreto, no *"),
                msg("Use WRITETIME(column_name) for a single non-PK cell.",
                        "Use WRITETIME(nombre_columna) para una celda no PK."),
                "CQL-WT-001",
                msg("WRITETIME is not available for primary key columns",
                        "WRITETIME no está disponible para columnas de clave primaria"),
                msg("Select WRITETIME only on regular (non-primary-key) columns.",
                        "Seleccione WRITETIME solo en columnas regulares (no PK)."));
        checkNativeAggregateOnColumn(t, out, ts, "TTL",
                "CQL-TTL-005",
                msg("TTL requires a specific column name, not *",
                        "TTL requiere un nombre de columna concreto, no *"),
                msg("Use TTL(column_name) for a single non-PK cell.",
                        "Use TTL(nombre_columna) para una celda no PK."),
                "CQL-TTL-004",
                msg("TTL is not available for primary key columns",
                        "TTL no está disponible para columnas de clave primaria"),
                msg("Select TTL only on regular (non-primary-key) columns.",
                        "Seleccione TTL solo en columnas regulares (no PK)."));
    }

    private void checkNativeAggregateOnColumn(
            List<CqlToken> t,
            List<SemanticError> out,
            TableSchema ts,
            String fnUpper,
            String starCode,
            String starMsg,
            String starSug,
            String pkCode,
            String pkMsg,
            String pkSug) {
        for (int i = 0; i < t.size(); i++) {
            boolean isFn = isKw(t, i, fnUpper)
                    || (t.get(i).type() == CqlTokenType.IDENTIFIER
                    && fnUpper.equalsIgnoreCase(t.get(i).value()));
            if (!isFn) {
                continue;
            }
            if (i + 1 >= t.size() || t.get(i + 1).type() != CqlTokenType.LEFT_PAREN) {
                continue;
            }
            int close = findMatchingParen(t, i + 1);
            if (close < 0) {
                continue;
            }
            boolean hasStar = false;
            for (int k = i + 2; k < close; k++) {
                if (t.get(k).type() == CqlTokenType.ASTERISK) {
                    hasStar = true;
                    break;
                }
            }
            if (hasStar) {
                out.add(new SemanticError(starCode, starMsg, starSug, SemanticError.Severity.ERROR));
            }
            for (int k = i + 2; k < close; k++) {
                if (isNameToken(t.get(k)) && ts != null && ts.isAnyPk(normalizeIdent(t.get(k).value()))) {
                    out.add(new SemanticError(pkCode, pkMsg, pkSug, SemanticError.Severity.ERROR));
                    break;
                }
            }
        }
    }

    private static String extractSimpleFromTable(List<CqlToken> t) {
        int f = indexOfKeyword(t, "FROM");
        if (f < 0) {
            return null;
        }
        int j = skipQualifiedName(t, f + 1);
        if (j < 0) {
            return null;
        }
        return qualifiedNameBefore(t, j);
    }

    private void checkLowTtl(List<CqlToken> t, List<SemanticError> out) {
        for (int i = 0; i + 2 < t.size(); i++) {
            if (!isKw(t, i, "USING") || !isKw(t, i + 1, "TTL")) {
                continue;
            }
            CqlToken num = t.get(i + 2);
            if (num.type() != CqlTokenType.NUMBER) {
                continue;
            }
            try {
                int v = parsePositiveInt(num.value());
                if (v > 0 && v < 60) {
                    out.add(new SemanticError(
                            "CQL-TTL-001",
                            msg("TTL below 60 seconds may cause unexpected data loss",
                                    "TTL menor de 60 segundos puede causar pérdida de datos inesperada"),
                            msg("Use TTL >= 60 seconds or omit TTL for persistent data",
                                    "Use TTL >= 60 s u omita TTL para datos persistentes"),
                            SemanticError.Severity.WARNING));
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static boolean hasLwtIfNotExists(List<CqlToken> t) {
        int values = indexOfKeyword(t, "VALUES");
        if (values < 0) {
            return false;
        }
        for (int i = values; i + 3 < t.size(); i++) {
            if (isKw(t, i, "IF") && isKw(t, i + 1, "NOT") && isKw(t, i + 2, "EXISTS")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLwtIfExistsOnly(List<CqlToken> t) {
        int w = indexOfKeyword(t, "WHERE");
        int lim = w >= 0 ? w : t.size();
        for (int i = 0; i + 1 < lim; i++) {
            if (isKw(t, i, "IF") && isKw(t, i + 1, "EXISTS") && (i + 2 >= lim || !isKw(t, i + 2, "NOT"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLwtIfExpressionAfterWhere(List<CqlToken> t) {
        int w = indexOfKeyword(t, "WHERE");
        if (w < 0) {
            return false;
        }
        for (int i = w + 1; i + 1 < t.size(); i++) {
            if (isKw(t, i, "IF") && !isKw(t, i + 1, "EXISTS") && !isKw(t, i + 1, "NOT")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLwtIfExistsAfterWhere(List<CqlToken> t) {
        int w = indexOfKeyword(t, "WHERE");
        if (w < 0) {
            return false;
        }
        int depth = 0;
        for (int i = w + 1; i + 1 < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth != 0) {
                continue;
            }
            if (isKw(t, i, "IF") && isKw(t, i + 1, "EXISTS")
                    && (i + 2 >= t.size() || !isKw(t, i + 2, "NOT"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHotPartitionLwt(List<CqlToken> tokens, String kind, SchemaRegistry registry) {
        String table = switch (kind) {
            case "INSERT" -> extractInsertTable(tokens);
            case "UPDATE", "DELETE" -> extractUpdateDeleteTable(tokens, kind);
            default -> null;
        };
        TableSchema ts = table != null ? registry.getTable(table) : null;
        if (ts != null && !ts.partitionKeys.isEmpty()) {
            for (String pk : ts.partitionKeys) {
                if (highCardinalityHint(pk)) {
                    return true;
                }
            }
            return false;
        }
        int w = indexOfKeyword(tokens, "WHERE");
        if (w < 0) {
            return false;
        }
        Map<String, String> eq = extractTopLevelEqualities(tokens, w + 1);
        for (String col : eq.keySet()) {
            if (highCardinalityHint(col)) {
                return true;
            }
        }
        return false;
    }

    private void analyzeLwtIfClause(List<CqlToken> t, String kind, List<SemanticError> out, SchemaRegistry registry) {
        int ifIdx = findConditionalIfIndex(t);
        if (ifIdx < 0) {
            return;
        }
        String table = switch (kind) {
            case "UPDATE", "DELETE" -> extractUpdateDeleteTable(t, kind);
            case "INSERT" -> extractInsertTable(t);
            default -> null;
        };
        TableSchema ts = table != null ? registry.getTable(table) : null;
        Set<String> condCols = extractIdentifiersInIfClause(t, ifIdx);
        if (ts != null) {
            for (String col : condCols) {
                if (ts.isAnyPk(normalizeIdent(col))) {
                    out.add(new SemanticError(
                            "CQL-LWT-002",
                            msg("LWT conditions cannot reference partition key or clustering columns",
                                    "Condiciones LWT no pueden referenciar columnas de partición o clustering"),
                            msg("Move the condition to regular columns only; PK columns belong in WHERE for UPDATE/DELETE.",
                                    "Mueva la condición solo a columnas regulares; las PK van en WHERE en UPDATE/DELETE."),
                            SemanticError.Severity.ERROR));
                    return;
                }
                if (!ts.hasColumn(normalizeIdent(col))) {
                    out.add(new SemanticError(
                            "CQL-LWT-005",
                            msg("LWT IF condition references unknown column for this table (from earlier CREATE in script)",
                                    "Condición LWT IF referencia columna desconocida para esta tabla (CREATE anterior en el script)"),
                            msg("Use column names defined in CREATE TABLE or match your real schema.",
                            "Use nombres definidos en CREATE TABLE o alinee con su esquema real."),
                            SemanticError.Severity.ERROR));
                    return;
                }
            }
        }
    }

    private static int findConditionalIfIndex(List<CqlToken> t) {
        if (hasLwtIfNotExists(t)) {
            return -1;
        }
        int w = indexOfKeyword(t, "WHERE");
        if (w < 0) {
            return indexOfKeyword(t, "IF");
        }
        for (int i = w + 1; i < t.size(); i++) {
            if (isKw(t, i, "IF")) {
                if (i + 1 < t.size() && isKw(t, i + 1, "EXISTS")) {
                    continue;
                }
                return i;
            }
        }
        int firstIf = indexOfKeyword(t, "IF");
        if (firstIf >= 0 && firstIf + 2 < t.size()
                && isKw(t, firstIf + 1, "NOT") && isKw(t, firstIf + 2, "EXISTS")) {
            return -1;
        }
        return firstIf;
    }

    private static Set<String> extractIdentifiersInIfClause(List<CqlToken> t, int ifIdx) {
        Set<String> acc = new HashSet<>();
        int depth = 0;
        for (int i = ifIdx + 1; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0
                    && (tok.type() == CqlTokenType.SEMICOLON || tok.type() == CqlTokenType.EOF)) {
                break;
            }
            if (tok.type() == CqlTokenType.IDENTIFIER && !"?".equals(tok.value())) {
                acc.add(tok.value());
            }
        }
        return acc;
    }

    private static int countPlusMinusAssignOps(List<CqlToken> t) {
        int c = 0;
        for (CqlToken tok : t) {
            if (tok.type() == CqlTokenType.OPERATOR) {
                String v = tok.value();
                if ("+=".equals(v) || "-=".equals(v)) {
                    c++;
                }
            }
        }
        return c;
    }

    private static boolean isBeginBatchStatement(List<CqlToken> t) {
        int i = firstSignificant(t);
        if (i < 0 || !isKw(t, i, "BEGIN")) {
            return false;
        }
        int j = i + 1;
        if (j < t.size() && isKw(t, j, "LOGGED")) {
            j++;
        } else if (j < t.size() && isKw(t, j, "UNLOGGED")) {
            j++;
        }
        if (j < t.size() && isKw(t, j, "COUNTER")) {
            j++;
        }
        return j < t.size() && isKw(t, j, "BATCH");
    }

    private static String extractUpdateDeleteTable(List<CqlToken> t, String kind) {
        if ("UPDATE".equals(kind)) {
            int u = indexOfKeyword(t, "UPDATE");
            if (u < 0) {
                return null;
            }
            int j = skipQualifiedName(t, u + 1);
            if (j < 0) {
                return null;
            }
            return qualifiedNameBefore(t, j);
        }
        if ("DELETE".equals(kind)) {
            int f = indexOfKeyword(t, "FROM");
            if (f < 0) {
                return null;
            }
            int j = skipQualifiedName(t, f + 1);
            if (j < 0) {
                return null;
            }
            return qualifiedNameBefore(t, j);
        }
        return null;
    }

    private static String extractInsertTable(List<CqlToken> t) {
        int ins = indexOfKeyword(t, "INSERT");
        if (ins < 0 || !keywordSequenceAt(t, ins, "INSERT", "INTO")) {
            return null;
        }
        int j = skipQualifiedName(t, ins + 2);
        if (j < 0) {
            return null;
        }
        return qualifiedNameBefore(t, j);
    }

    private static boolean keywordSequenceAt(List<CqlToken> tok, int from, String... words) {
        if (from < 0) {
            return false;
        }
        int j = from;
        for (String w : words) {
            if (j >= tok.size()) {
                return false;
            }
            CqlToken t = tok.get(j);
            if (t.type() != CqlTokenType.KEYWORD || !w.equalsIgnoreCase(t.value())) {
                return false;
            }
            j++;
        }
        return true;
    }

    private static int indexOfKeyword(List<CqlToken> t, String word) {
        return indexOfKeywordFrom(t, 0, word);
    }

    private static int indexOfKeywordFrom(List<CqlToken> t, int from, String word) {
        for (int i = from; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.EOF) {
                break;
            }
            if (tok.type() == CqlTokenType.KEYWORD && word.equalsIgnoreCase(tok.value())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isKw(List<CqlToken> t, int i, String word) {
        if (i < 0 || i >= t.size()) {
            return false;
        }
        CqlToken tok = t.get(i);
        return tok.type() == CqlTokenType.KEYWORD && word.equalsIgnoreCase(tok.value());
    }

    private static boolean hasWhereAtDepthZero(List<CqlToken> t) {
        int depth = 0;
        for (CqlToken tok : t) {
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && tok.type() == CqlTokenType.KEYWORD
                    && "WHERE".equalsIgnoreCase(tok.value())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOrderBy(List<CqlToken> t) {
        int depth = 0;
        for (int i = 0; i + 1 < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && isKw(t, i, "ORDER") && isKw(t, i + 1, "BY")) {
                if (i >= 1 && isKw(t, i - 1, "CLUSTERING")) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean containsAllowFiltering(List<CqlToken> t) {
        int depth = 0;
        for (int i = 0; i + 1 < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && isKw(t, i, "ALLOW") && isKw(t, i + 1, "FILTERING")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsJoinWord(List<CqlToken> t) {
        int depth = 0;
        for (CqlToken tok : t) {
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0
                    && tok.type() == CqlTokenType.IDENTIFIER
                    && "join".equalsIgnoreCase(tok.value())) {
                return true;
            }
        }
        return false;
    }

    private static int skipIfNotExistsClause(List<CqlToken> t, int j) {
        if (isKw(t, j, "IF")) {
            if (j + 2 < t.size() && isKw(t, j + 1, "NOT") && isKw(t, j + 2, "EXISTS")) {
                return j + 3;
            }
            if (j + 1 < t.size() && isKw(t, j + 1, "EXISTS")) {
                return j + 2;
            }
        }
        return j;
    }

    private static int consumeQualifiedTableName(List<CqlToken> t, int j) {
        if (j < 0 || j >= t.size()) {
            return -1;
        }
        if (!isNameToken(t.get(j))) {
            return -1;
        }
        j++;
        while (j < t.size() && t.get(j).type() == CqlTokenType.DOT) {
            j++;
            if (j >= t.size() || !isNameToken(t.get(j))) {
                return -1;
            }
            j++;
        }
        return j;
    }

    private static boolean isNameToken(CqlToken tok) {
        return tok.type() == CqlTokenType.IDENTIFIER
                || tok.type() == CqlTokenType.UUID_LITERAL;
    }

    private static int parsePositiveInt(String lexeme) {
        String s = lexeme.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("0x")) {
            return Integer.parseUnsignedInt(s.substring(2), 16);
        }
        int end = s.length();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '.' || c == 'e') {
                end = k;
                break;
            }
        }
        s = s.substring(0, end);
        return Integer.parseInt(s);
    }

    private static long parseLongNumber(String lexeme) {
        String s = lexeme.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("0x")) {
            return Long.parseUnsignedLong(s.substring(2), 16);
        }
        int end = s.length();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '.' || c == 'e') {
                end = k;
                break;
            }
        }
        s = s.substring(0, end);
        return Long.parseLong(s);
    }

    private static String normalizeIdent(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("\"", "");
    }

    private static String normalizeTableKey(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("\"", "");
    }

    private static Set<String> buildPrimitives() {
        return Set.of(
                "text", "ascii", "varchar", "bigint", "blob", "boolean", "counter", "date", "decimal",
                "double", "duration", "float", "inet", "int", "list", "map", "set", "smallint", "time",
                "timestamp", "timeuuid", "tinyint", "tuple", "uuid", "varint", "frozen");
    }

    private static TableSchema parseCreateTableSchema(List<CqlToken> t, int c) {
        int j = c + 2;
        j = skipIfNotExistsClause(t, j);
        j = consumeQualifiedTableName(t, j);
        if (j < 0) {
            return null;
        }
        String tableKey = qualifiedNameBefore(t, j).toLowerCase(Locale.ROOT);
        TableSchema schema = new TableSchema(tableKey);
        if (j >= t.size() || t.get(j).type() != CqlTokenType.LEFT_PAREN) {
            return schema;
        }
        int close = findMatchingParen(t, j);
        if (close < 0) {
            return schema;
        }
        fillSchemaFromColumnList(t, j, close, schema);
        return schema;
    }

    private static void fillSchemaFromColumnList(List<CqlToken> t, int colOpen, int colClose, TableSchema schema) {
        PkParts pk = extractPkPartsFromColumnList(t, colOpen, colClose);
        if (pk != null) {
            schema.partitionKeys.addAll(pk.partition);
            schema.clusteringKeys.addAll(pk.clustering);
        }
        int d = 1;
        int segStart = colOpen + 1;
        for (int i = colOpen + 1; i < colClose; i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                d++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                d--;
            }
            if (d == 1 && tok.type() == CqlTokenType.COMMA) {
                addColumnDefinitionName(t, segStart, i, schema);
                segStart = i + 1;
            }
        }
        addColumnDefinitionName(t, segStart, colClose, schema);
    }

    private static void addColumnDefinitionName(List<CqlToken> t, int start, int end, TableSchema schema) {
        if (start >= end) {
            return;
        }
        if (isKw(t, start, "PRIMARY") && start + 1 < end && isKw(t, start + 1, "KEY")) {
            return;
        }
        if (isNameToken(t.get(start))) {
            schema.columns.add(normalizeIdent(t.get(start).value()));
        }
    }

    private static PkParts extractPkPartsFromColumnList(List<CqlToken> t, int colOpen, int colClose) {
        int d = 1;
        for (int i = colOpen + 1; i < colClose; i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                d++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                d--;
            }
            if (d == 1 && isKw(t, i, "PRIMARY") && i + 2 < t.size() && isKw(t, i + 1, "KEY")
                    && t.get(i + 2).type() == CqlTokenType.LEFT_PAREN) {
                int pkOpen = i + 2;
                int pkClose = findMatchingParen(t, pkOpen);
                if (pkClose < 0) {
                    return null;
                }
                return parsePkInner(t, pkOpen + 1, pkClose);
            }
        }
        return null;
    }

    private static PkParts parsePkInner(List<CqlToken> t, int innerStart, int innerEnd) {
        PkParts p = new PkParts();
        if (innerStart >= innerEnd) {
            return p;
        }
        if (t.get(innerStart).type() == CqlTokenType.LEFT_PAREN) {
            int innerClose = findMatchingParen(t, innerStart);
            if (innerClose < 0) {
                return p;
            }
            for (String c : splitTopLevelCommaIdents(t, innerStart + 1, innerClose)) {
                p.partition.add(normalizeIdent(c));
            }
            for (String c : splitTopLevelCommaIdents(t, innerClose + 1, innerEnd)) {
                p.clustering.add(normalizeIdent(c));
            }
        } else {
            List<String> parts = splitTopLevelCommaIdents(t, innerStart, innerEnd);
            if (!parts.isEmpty()) {
                p.partition.add(normalizeIdent(parts.get(0)));
                for (int k = 1; k < parts.size(); k++) {
                    p.clustering.add(normalizeIdent(parts.get(k)));
                }
            }
        }
        return p;
    }

    /**
     * Parsed partition vs clustering keys from a {@code PRIMARY KEY (...)} clause inside {@code CREATE TABLE}.
     */
    private static final class PkParts {
        final Set<String> partition = new HashSet<>();
        final Set<String> clustering = new HashSet<>();
    }

    private static final class TableSchema {
        final String tableKey;
        final Set<String> columns = new HashSet<>();
        final Set<String> partitionKeys = new HashSet<>();
        final Set<String> clusteringKeys = new HashSet<>();

        TableSchema(String tableKey) {
            this.tableKey = tableKey;
        }

        Set<String> allPkColumns() {
            Set<String> s = new HashSet<>(partitionKeys);
            s.addAll(clusteringKeys);
            return s;
        }

        boolean hasColumn(String norm) {
            return columns.contains(norm);
        }

        boolean isAnyPk(String norm) {
            return partitionKeys.contains(norm) || clusteringKeys.contains(norm);
        }

        boolean isPartitionKey(String norm) {
            return partitionKeys.contains(norm);
        }
    }

    private static final Set<String> CASSANDRA_SYSTEM_KEYSPACES = Set.of(
            "system", "system_schema", "system_virtual_schema", "system_auth",
            "system_distributed", "system_traces", "system_views");

    private record UdfLangBody(boolean java, String body) {}

    private void analyzeCreateFunction(List<CqlToken> tokens, List<SemanticError> out) {
        int c = indexOfKeyword(tokens, "CREATE");
        if (c < 0) {
            return;
        }
        if (!keywordSequenceAt(tokens, c, "CREATE", "FUNCTION")
                && !keywordSequenceAt(tokens, c, "CREATE", "OR", "REPLACE", "FUNCTION")) {
            return;
        }
        if (!hasKeyword(tokens, "DETERMINISTIC")) {
            out.add(new SemanticError(
                    "CAS-UDF-001",
                    msg("CQL user-defined function has no DETERMINISTIC clause — engine cannot assert stable results",
                            "La UDF en CQL no declara DETERMINISTIC — el motor no puede asegurar resultados estables"),
                    msg("If supported, add DETERMINISTIC / MONOTONIC, or keep the body side-effect free.",
                            "Si está soportado, añada DETERMINISTIC / MONOTONIC, o mantenga el cuerpo sin efectos secundarios."),
                    SemanticError.Severity.INFO));
        }
        UdfLangBody ulb = extractUdfLanguageAndBody(tokens);
        if (ulb.body() == null || ulb.body().isBlank()) {
            return;
        }
        out.addAll(CassandraJsParser.analyzeUdfBody(ulb.body(), ulb.java(), ui));
    }

    private void analyzeCreateAggregate(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        if (indexOfKeyword(tokens, "INITCOND") < 0) {
            out.add(new SemanticError(
                    "CAS-UDA-001",
                    msg("CREATE AGGREGATE without INITCOND — aggregate initial state is unspecified",
                            "CREATE AGGREGATE sin INITCOND — el estado inicial del agregado no está especificado"),
                    msg("Add INITCOND with a literal (or NULL) matching STYPE.",
                            "Añada INITCOND con un literal (o NULL) acorde a STYPE."),
                    SemanticError.Severity.WARNING));
        }
        String sfunc = extractQualifiedNameAfterKeyword(tokens, "SFUNC");
        if (sfunc == null || sfunc.isBlank()) {
            return;
        }
        if (!registry.hasFunction(sfunc)) {
            out.add(new SemanticError(
                    "CAS-UDA-002",
                    msg("SFUNC is not defined in this script context: " + sfunc,
                            "SFUNC no está definida en el contexto de este script: " + sfunc),
                    msg("CREATE the state function first or correct the SFUNC name/keyspace.",
                            "Cree primero la función de estado o corrija el nombre/keyspace de SFUNC."),
                    SemanticError.Severity.ERROR));
        }
    }

    private void analyzeCreateTrigger(List<CqlToken> tokens, List<SemanticError> out) {
        out.add(new SemanticError(
                "CAS-TRG-001",
                msg("Trigger Java class cannot be verified statically — classpath is not available here",
                        "No verificable estáticamente: la clase Java del trigger no está en el classpath de análisis"),
                msg("Deploy the JAR on every node and validate in staging.",
                        "Despliegue el JAR en cada nodo y valide en staging."),
                SemanticError.Severity.WARNING));
        int on = indexOfKeyword(tokens, "ON");
        if (on < 0 || on + 1 >= tokens.size()) {
            return;
        }
        int j = skipQualifiedName(tokens, on + 1);
        if (j < 0) {
            return;
        }
        String table = qualifiedNameBefore(tokens, j);
        if (isSystemKeyspaceTable(table)) {
            out.add(new SemanticError(
                    "CAS-TRG-002",
                    msg("Trigger on a system keyspace table is invalid", "Trigger sobre tablas de sistema no permitido"),
                    msg("Use application keyspaces only for triggers.",
                            "Use solo keyspaces de aplicación para triggers."),
                    SemanticError.Severity.ERROR));
        }
    }

    private static boolean hasKeyword(List<CqlToken> t, String word) {
        for (int i = 0; i < t.size(); i++) {
            if (isKw(t, i, word)) {
                return true;
            }
        }
        return false;
    }

    private static UdfLangBody extractUdfLanguageAndBody(List<CqlToken> t) {
        int langKw = -1;
        for (int i = 0; i < t.size(); i++) {
            if (isKw(t, i, "LANGUAGE")) {
                langKw = i;
                break;
            }
        }
        boolean javaLang = true;
        if (langKw >= 0 && langKw + 1 < t.size()) {
            String lv = t.get(langKw + 1).value().toLowerCase(Locale.ROOT);
            javaLang = !lv.equals("javascript");
        }
        for (int i = 0; i + 1 < t.size(); i++) {
            if (isKw(t, i, "AS") && t.get(i + 1).type() == CqlTokenType.STRING) {
                return new UdfLangBody(javaLang, cqlStringLiteralValue(t.get(i + 1).value()));
            }
        }
        return new UdfLangBody(javaLang, "");
    }

    private static String cqlStringLiteralValue(String lexeme) {
        if (lexeme == null) {
            return "";
        }
        if (lexeme.length() >= 2 && lexeme.charAt(0) == '\'' && lexeme.charAt(lexeme.length() - 1) == '\'') {
            return lexeme.substring(1, lexeme.length() - 1).replace("''", "'");
        }
        return lexeme;
    }

    private static String extractQualifiedNameAfterKeyword(List<CqlToken> t, String kw) {
        int i = indexOfKeyword(t, kw);
        if (i < 0) {
            return null;
        }
        int j = skipQualifiedName(t, i + 1);
        if (j < 0) {
            return null;
        }
        return qualifiedNameBefore(t, j);
    }

    private static String extractFunctionQualifiedNameForRegistry(List<CqlToken> t, int createIdx) {
        int fk = indexOfKeywordFrom(t, createIdx, "FUNCTION");
        if (fk < 0) {
            return null;
        }
        int j = fk + 1;
        j = skipIfNotExistsClause(t, j);
        int after = skipQualifiedName(t, j);
        if (after < 0) {
            return null;
        }
        return qualifiedNameBefore(t, after);
    }

    private static boolean isSystemKeyspaceTable(String qualified) {
        if (qualified == null || qualified.isBlank()) {
            return false;
        }
        String k = normalizeTableKey(qualified);
        int dot = k.indexOf('.');
        if (dot < 0) {
            return false;
        }
        String ks = k.substring(0, dot);
        return CASSANDRA_SYSTEM_KEYSPACES.contains(ks);
    }

    private void analyzeDropStatement(List<CqlToken> tokens, List<SemanticError> out) {
        int d = indexOfKeyword(tokens, "DROP");
        if (d < 0) {
            return;
        }
        if (keywordSequenceAt(tokens, d, "DROP", "ROLE") || keywordSequenceAt(tokens, d, "DROP", "USER")) {
            return;
        }
    }

    private void analyzeDclList(List<CqlToken> tokens, List<SemanticError> out) {
        // LIST ROLES / LIST USERS / LIST … PERMISSIONS — metadata; no CAS-DCL product rules.
    }

    private void analyzeDclAuthShell(List<CqlToken> tokens, List<SemanticError> out) {
        // LOGIN / LOGOUT are cqlsh directives, not server CQL; no product codes required here.
    }

    private void analyzeDclCreateRole(List<CqlToken> tokens, List<SemanticError> out) {
        int c = indexOfKeyword(tokens, "CREATE");
        if (c < 0) {
            return;
        }
        String roleName = extractRoleNameAfterCreateRole(tokens, c);
        if (roleName == null) {
            return;
        }
        int afterName = indexAfterRoleNameTokens(tokens, c);
        if (afterName < 0) {
            return;
        }
        int withIdx = indexOfKeywordDepthZero(tokens, afterName, "WITH");
        RoleWithScan scan = scanRoleWithClause(tokens, withIdx);
        if (!scan.hasPassword) {
            out.add(new SemanticError(
                    "CAS-DCL-001",
                    msg("CREATE ROLE without PASSWORD is rejected / unsafe for login-capable roles",
                            "CREATE ROLE sin PASSWORD es inseguro o inválido para roles con capacidad de login"),
                    msg("Add WITH PASSWORD = '…' (and consider LOGIN/SUPERUSER explicitly).",
                            "Añada WITH PASSWORD = '…' (y defina LOGIN/SUPERUSER explícitamente)."),
                    SemanticError.Severity.ERROR));
        }
        if (Boolean.TRUE.equals(scan.superuser)) {
            out.add(new SemanticError(
                    "CAS-DCL-003",
                    msg("SUPERUSER = true grants unrestricted cluster access — use sparingly",
                            "SUPERUSER = true otorga acceso ilimitado al clúster — úselo con extrema moderación"),
                    msg("Prefer narrow roles with explicit GRANT on keyspaces/tables.",
                            "Prefiera roles acotados con GRANT explícito sobre keyspaces/tablas."),
                    SemanticError.Severity.WARNING));
        }
        for (String pw : scan.passwordLiterals) {
            maybeAddWeakPasswordWarning(out, pw);
        }
    }

    private void analyzeDclCreateUser(List<CqlToken> tokens, List<SemanticError> out) {
        int c = indexOfKeyword(tokens, "CREATE");
        if (c < 0 || !keywordSequenceAt(tokens, c, "CREATE", "USER")) {
            return;
        }
        int j = c + 2;
        j = skipIfNotExistsClause(tokens, j);
        if (j < 0 || j >= tokens.size() || !isNameToken(tokens.get(j))) {
            return;
        }
        int withIdx = indexOfKeywordDepthZero(tokens, skipQualifiedName(tokens, j), "WITH");
        RoleWithScan scan = scanRoleWithClause(tokens, withIdx);
        for (String pw : scan.passwordLiterals) {
            maybeAddWeakPasswordWarning(out, pw);
        }
    }

    private void analyzeDclAlterRole(List<CqlToken> tokens, List<SemanticError> out) {
        int a = indexOfKeyword(tokens, "ALTER");
        if (a < 0 || !keywordSequenceAt(tokens, a, "ALTER", "ROLE")) {
            return;
        }
        int j = a + 2;
        if (j >= tokens.size() || !isNameToken(tokens.get(j))) {
            return;
        }
        int afterName = skipQualifiedName(tokens, j);
        if (afterName < 0) {
            return;
        }
        int withIdx = indexOfKeywordDepthZero(tokens, afterName, "WITH");
        RoleWithScan scan = scanRoleWithClause(tokens, withIdx);
        if (Boolean.TRUE.equals(scan.superuser)) {
            out.add(new SemanticError(
                    "CAS-DCL-003",
                    msg("SUPERUSER = true grants unrestricted cluster access — use sparingly",
                            "SUPERUSER = true otorga acceso ilimitado al clúster — úselo con extrema moderación"),
                    msg("Prefer narrow roles with explicit GRANT on keyspaces/tables.",
                            "Prefiera roles acotados con GRANT explícito sobre keyspaces/tablas."),
                    SemanticError.Severity.WARNING));
        }
        for (String pw : scan.passwordLiterals) {
            maybeAddWeakPasswordWarning(out, pw);
        }
    }

    private void analyzeDclAlterUser(List<CqlToken> tokens, List<SemanticError> out) {
        int a = indexOfKeyword(tokens, "ALTER");
        if (a < 0 || !keywordSequenceAt(tokens, a, "ALTER", "USER")) {
            return;
        }
        int j = a + 2;
        if (j >= tokens.size() || !isNameToken(tokens.get(j))) {
            return;
        }
        int afterName = skipQualifiedName(tokens, j);
        if (afterName < 0) {
            return;
        }
        int withIdx = indexOfKeywordDepthZero(tokens, afterName, "WITH");
        RoleWithScan scan = scanRoleWithClause(tokens, withIdx);
        for (String pw : scan.passwordLiterals) {
            maybeAddWeakPasswordWarning(out, pw);
        }
    }

    private void analyzeDclGrant(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        int g = indexOfKeyword(tokens, "GRANT");
        if (g < 0) {
            return;
        }
        int onIdx = indexOfKeywordDepthZero(tokens, g + 1, "ON");
        int toIdx = indexOfKeywordDepthZero(tokens, g + 1, "TO");
        boolean permissionGrant = onIdx >= 0 && toIdx > onIdx;
        if (permissionGrant) {
            if (grantRevokeHasAllBeforeOn(tokens, g + 1, onIdx)) {
                out.add(new SemanticError(
                        "CAS-DCL-002",
                        msg("GRANT ALL (or ALL PERMISSIONS) is overly broad — prefer least privilege",
                                "GRANT ALL (o ALL PERMISSIONS) es demasiado amplio — prefiera el menor privilegio"),
                        msg("Grant only the permissions each role needs on specific resources.",
                            "Otorgue solo los permisos necesarios sobre recursos concretos."),
                        SemanticError.Severity.WARNING));
            }
            if (grantRevokeOnKeyspaceResource(tokens, onIdx)) {
                out.add(new SemanticError(
                        "CAS-DCL-004",
                        msg("GRANT on a whole keyspace affects every table in that keyspace",
                                "GRANT sobre un keyspace completo afecta todas las tablas de ese keyspace"),
                        msg("If you only need one table, grant on TABLE keyspace.table instead.",
                            "Si solo necesita una tabla, use GRANT sobre TABLE keyspace.tabla."),
                        SemanticError.Severity.INFO));
            }
            return;
        }
        if (toIdx < 0) {
            return;
        }
        String granted = firstRoleIdentifierBetween(tokens, g + 1, toIdx);
        String grantee = firstRoleIdentifierAfter(tokens, toIdx + 1);
        if (granted == null || grantee == null) {
            return;
        }
        if (registry.hasNonLoginGrantedToLoginUser(granted, grantee)) {
            out.add(new SemanticError(
                    "CAS-DCL-005",
                    msg("Granting a non-LOGIN role to a LOGIN-capable role/user is usually unintended",
                            "Asignar un rol sin LOGIN a un usuario/rol con LOGIN suele ser un error de diseño"),
                    msg("Enable LOGIN on the delegated role or grant a LOGIN role instead.",
                            "Active LOGIN en el rol delegado o asigne un rol con LOGIN."),
                    SemanticError.Severity.WARNING));
        }
    }

    private void analyzeDclRevoke(List<CqlToken> tokens, List<SemanticError> out, SchemaRegistry registry) {
        int r = indexOfKeyword(tokens, "REVOKE");
        if (r < 0) {
            return;
        }
        int onIdx = indexOfKeywordDepthZero(tokens, r + 1, "ON");
        int fromIdx = indexOfKeywordDepthZero(tokens, r + 1, "FROM");
        boolean permissionRevoke = onIdx >= 0 && fromIdx > onIdx;
        if (permissionRevoke) {
            if (grantRevokeHasAllBeforeOn(tokens, r + 1, onIdx)) {
                out.add(new SemanticError(
                        "CAS-DCL-002",
                        msg("REVOKE ALL (or ALL PERMISSIONS) removes every privilege — double-check scope",
                                "REVOKE ALL (o ALL PERMISSIONS) elimina todos los privilegios — verifique el alcance"),
                        msg("Prefer revoking specific permissions to avoid accidental lock-out.",
                            "Prefiera revocar permisos concretos para evitar bloqueos accidentales."),
                        SemanticError.Severity.WARNING));
            }
            if (grantRevokeOnKeyspaceResource(tokens, onIdx)) {
                out.add(new SemanticError(
                        "CAS-DCL-004",
                        msg("REVOKE on a whole keyspace affects every table in that keyspace",
                                "REVOKE sobre un keyspace completo afecta todas las tablas de ese keyspace"),
                        msg("If you only target one table, revoke on TABLE keyspace.table instead.",
                            "Si solo afecta una tabla, use REVOKE sobre TABLE keyspace.tabla."),
                        SemanticError.Severity.INFO));
            }
        }
    }

    private void maybeAddWeakPasswordWarning(List<SemanticError> out, String passwordInner) {
        if (passwordInner == null || passwordInner.isEmpty()) {
            return;
        }
        if (isWeakCassandraPassword(passwordInner)) {
            out.add(new SemanticError(
                    "CAS-AUTH-001",
                    msg("Weak PASSWORD: shorter than 8 characters or only letters/digits",
                            "PASSWORD débil: menos de 8 caracteres o solo letras y dígitos"),
                    msg("Use at least 8 characters and include symbols for interactive accounts.",
                            "Use al menos 8 caracteres e incluya símbolos para cuentas interactivas."),
                    SemanticError.Severity.WARNING));
        }
    }

    private static boolean isWeakCassandraPassword(String p) {
        if (p.length() < 8) {
            return true;
        }
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (!Character.isLetterOrDigit(ch)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfKeywordDepthZero(List<CqlToken> t, int from, String word) {
        int depth = 0;
        for (int i = Math.max(0, from); i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && isKw(t, i, word)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean grantRevokeHasAllBeforeOn(List<CqlToken> t, int from, int onIdx) {
        int depth = 0;
        for (int i = from; i < onIdx && i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && isKw(t, i, "ALL")) {
                return true;
            }
        }
        return false;
    }

    private static boolean grantRevokeOnKeyspaceResource(List<CqlToken> t, int onIdx) {
        int j = onIdx + 1;
        if (j < t.size() && isKw(t, j, "ALL") && j + 1 < t.size() && isKw(t, j + 1, "KEYSPACES")) {
            return true;
        }
        return j < t.size() && isKw(t, j, "KEYSPACE");
    }

    private static String extractRoleNameAfterCreateRole(List<CqlToken> t, int createIdx) {
        int roleTok;
        if (keywordSequenceAt(t, createIdx, "CREATE", "OR", "REPLACE", "ROLE")) {
            roleTok = createIdx + 3;
        } else if (keywordSequenceAt(t, createIdx, "CREATE", "ROLE")) {
            roleTok = createIdx + 1;
        } else {
            return null;
        }
        if (!isKw(t, roleTok, "ROLE")) {
            return null;
        }
        int j = roleTok + 1;
        j = skipIfNotExistsClause(t, j);
        if (j < 0 || j >= t.size() || !isNameToken(t.get(j))) {
            return null;
        }
        int after = skipQualifiedName(t, j);
        if (after < 0) {
            return null;
        }
        return qualifiedNameBefore(t, after);
    }

    private static int indexAfterRoleNameTokens(List<CqlToken> t, int createIdx) {
        int roleTok;
        if (keywordSequenceAt(t, createIdx, "CREATE", "OR", "REPLACE", "ROLE")) {
            roleTok = createIdx + 3;
        } else if (keywordSequenceAt(t, createIdx, "CREATE", "ROLE")) {
            roleTok = createIdx + 1;
        } else {
            return -1;
        }
        if (!isKw(t, roleTok, "ROLE")) {
            return -1;
        }
        int j = roleTok + 1;
        j = skipIfNotExistsClause(t, j);
        if (j < 0 || j >= t.size() || !isNameToken(t.get(j))) {
            return -1;
        }
        return skipQualifiedName(t, j);
    }

    private static String firstRoleIdentifierBetween(List<CqlToken> t, int from, int end) {
        for (int i = from; i < end && i < t.size(); i++) {
            if (isNameToken(t.get(i))) {
                return normalizeIdent(t.get(i).value());
            }
        }
        return null;
    }

    private static String firstRoleIdentifierAfter(List<CqlToken> t, int from) {
        for (int i = from; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.SEMICOLON || tok.type() == CqlTokenType.EOF) {
                break;
            }
            if (isNameToken(t.get(i))) {
                return normalizeIdent(t.get(i).value());
            }
        }
        return null;
    }

    private static RoleWithScan scanRoleWithClause(List<CqlToken> t, int withIdx) {
        List<String> pws = new ArrayList<>();
        if (withIdx < 0 || !isKw(t, withIdx, "WITH")) {
            return RoleWithScan.empty(pws);
        }
        Boolean login = null;
        Boolean superuser = null;
        boolean hasPassword = false;
        int depth = 0;
        for (int i = withIdx + 1; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (tok.type() == CqlTokenType.SEMICOLON || tok.type() == CqlTokenType.EOF)) {
                break;
            }
            if (depth != 0) {
                continue;
            }
            if (isKw(t, i, "PASSWORD")) {
                int k = i + 1;
                if (k < t.size() && t.get(k).type() == CqlTokenType.OPERATOR && "=".equals(t.get(k).value())) {
                    k++;
                }
                if (k < t.size() && t.get(k).type() == CqlTokenType.STRING) {
                    hasPassword = true;
                    pws.add(cqlStringLiteralValue(t.get(k).value()));
                }
            } else if (isKw(t, i, "LOGIN")) {
                Boolean b = readEqBooleanValue(t, i);
                if (b != null) {
                    login = b;
                }
            } else if (isKw(t, i, "SUPERUSER")) {
                Boolean b = readEqBooleanValue(t, i);
                if (b != null) {
                    superuser = b;
                }
            } else if (isKw(t, i, "NOSUPERUSER")) {
                superuser = false;
            }
        }
        return new RoleWithScan(true, hasPassword, login, superuser, pws);
    }

    private static Boolean readEqBooleanValue(List<CqlToken> t, int kwIdx) {
        int k = kwIdx + 1;
        if (k < t.size() && t.get(k).type() == CqlTokenType.OPERATOR && "=".equals(t.get(k).value())) {
            k++;
        }
        if (k >= t.size()) {
            return null;
        }
        if (t.get(k).type() == CqlTokenType.BOOLEAN) {
            return Boolean.parseBoolean(t.get(k).value());
        }
        return null;
    }

    private static String extractDropRoleOrUserName(List<CqlToken> t, int dropIdx, boolean user) {
        if (!isKw(t, dropIdx, "DROP")) {
            return null;
        }
        if (user && !keywordSequenceAt(t, dropIdx, "DROP", "USER")) {
            return null;
        }
        if (!user && !keywordSequenceAt(t, dropIdx, "DROP", "ROLE")) {
            return null;
        }
        int j = dropIdx + 2;
        j = skipIfNotExistsClause(t, j);
        if (j < 0 || j >= t.size() || !isNameToken(t.get(j))) {
            return null;
        }
        int after = skipQualifiedName(t, j);
        if (after < 0) {
            return null;
        }
        return qualifiedNameBefore(t, after);
    }

    private static void ingestRoleFromCreateRole(List<CqlToken> t, int createIdx, SchemaRegistry reg) {
        String name = extractRoleNameAfterCreateRole(t, createIdx);
        if (name == null) {
            return;
        }
        int after = indexAfterRoleNameTokens(t, createIdx);
        int withIdx = after >= 0 ? indexOfKeywordDepthZero(t, after, "WITH") : -1;
        RoleWithScan scan = scanRoleWithClause(t, withIdx);
        reg.putRoleFromScan(name, scan, false);
    }

    private static void ingestRoleFromAlterRole(List<CqlToken> t, int alterIdx, SchemaRegistry reg) {
        if (!keywordSequenceAt(t, alterIdx, "ALTER", "ROLE")) {
            return;
        }
        int j = alterIdx + 2;
        if (j < 0 || j >= t.size() || !isNameToken(t.get(j))) {
            return;
        }
        int after = skipQualifiedName(t, j);
        if (after < 0) {
            return;
        }
        String name = qualifiedNameBefore(t, after);
        int withIdx = indexOfKeywordDepthZero(t, after, "WITH");
        RoleWithScan scan = scanRoleWithClause(t, withIdx);
        reg.putRoleFromScan(name, scan, false);
    }

    private static void ingestRoleFromCreateUser(List<CqlToken> t, int createIdx, SchemaRegistry reg) {
        if (!keywordSequenceAt(t, createIdx, "CREATE", "USER")) {
            return;
        }
        int j = createIdx + 2;
        j = skipIfNotExistsClause(t, j);
        if (j < 0 || j >= t.size() || !isNameToken(t.get(j))) {
            return;
        }
        int after = skipQualifiedName(t, j);
        if (after < 0) {
            return;
        }
        String name = qualifiedNameBefore(t, after);
        int withIdx = indexOfKeywordDepthZero(t, after, "WITH");
        RoleWithScan scan = scanRoleWithClause(t, withIdx);
        reg.putRoleFromScan(name, scan, true);
    }

    private static void ingestRoleFromAlterUser(List<CqlToken> t, int alterIdx, SchemaRegistry reg) {
        if (!keywordSequenceAt(t, alterIdx, "ALTER", "USER")) {
            return;
        }
        int j = alterIdx + 2;
        if (j < 0 || j >= t.size() || !isNameToken(t.get(j))) {
            return;
        }
        int after = skipQualifiedName(t, j);
        if (after < 0) {
            return;
        }
        String name = qualifiedNameBefore(t, after);
        int withIdx = indexOfKeywordDepthZero(t, after, "WITH");
        RoleWithScan scan = scanRoleWithClause(t, withIdx);
        reg.putRoleFromScan(name, scan, true);
    }

    private static final class RoleWithScan {
        final boolean sawWith;
        final boolean hasPassword;
        final Boolean login;
        final Boolean superuser;
        final List<String> passwordLiterals;

        private RoleWithScan(boolean sawWith, boolean hasPassword, Boolean login, Boolean superuser, List<String> pws) {
            this.sawWith = sawWith;
            this.hasPassword = hasPassword;
            this.login = login;
            this.superuser = superuser;
            this.passwordLiterals = pws;
        }

        static RoleWithScan empty(List<String> buf) {
            return new RoleWithScan(false, false, null, null, List.copyOf(buf));
        }
    }

    private static final class SchemaRegistry {
        private final Map<String, TableSchema> tables = new HashMap<>();
        private final Map<String, Integer> mvByBase = new HashMap<>();
        private final Set<String> functions = new HashSet<>();
        private final Map<String, MutableRole> rolesByName = new HashMap<>();

        void registerFunction(String qualifiedOrSimple) {
            if (qualifiedOrSimple == null || qualifiedOrSimple.isBlank()) {
                return;
            }
            String k = normalizeTableKey(qualifiedOrSimple);
            functions.add(k);
            int dot = k.lastIndexOf('.');
            if (dot >= 0) {
                functions.add(k.substring(dot + 1));
            }
        }

        boolean hasFunction(String qualifiedOrSimple) {
            if (qualifiedOrSimple == null || qualifiedOrSimple.isBlank()) {
                return false;
            }
            String k = normalizeTableKey(qualifiedOrSimple);
            if (functions.contains(k)) {
                return true;
            }
            int dot = k.lastIndexOf('.');
            if (dot >= 0 && functions.contains(k.substring(dot + 1))) {
                return true;
            }
            return functions.contains(normalizeIdent(qualifiedOrSimple));
        }

        TableSchema getTable(String qualifiedName) {
            if (qualifiedName == null || qualifiedName.isBlank()) {
                return null;
            }
            String k = normalizeTableKey(qualifiedName);
            TableSchema ts = tables.get(k);
            if (ts != null) {
                return ts;
            }
            int dot = k.lastIndexOf('.');
            if (dot >= 0) {
                return tables.get(k.substring(dot + 1));
            }
            return null;
        }

        int getMvCount(String baseTable) {
            if (baseTable == null) {
                return 0;
            }
            return mvByBase.getOrDefault(normalizeTableKey(baseTable), 0);
        }

        private static final class MutableRole {
            boolean hasPassword;
            boolean login;
            boolean superuser;
        }

        /**
         * True when the granted role is known to have LOGIN = false and the grantee is known with LOGIN = true
         * (e.g. interactive user role), per CAS-DCL-005.
         */
        boolean hasNonLoginGrantedToLoginUser(String grantedRole, String granteeRole) {
            if (grantedRole == null || granteeRole == null) {
                return false;
            }
            MutableRole gr = rolesByName.get(normalizeIdent(grantedRole));
            MutableRole ge = rolesByName.get(normalizeIdent(granteeRole));
            return gr != null && !gr.login && ge != null && ge.login;
        }

        void putRoleFromScan(String name, RoleWithScan scan, boolean userAssumeLogin) {
            if (name == null || name.isBlank()) {
                return;
            }
            String k = normalizeIdent(name);
            MutableRole m = rolesByName.computeIfAbsent(k, x -> new MutableRole());
            if (scan.hasPassword) {
                m.hasPassword = true;
            }
            if (scan.login != null) {
                m.login = scan.login;
            } else if (userAssumeLogin) {
                m.login = true;
            }
            if (scan.superuser != null) {
                m.superuser = scan.superuser;
            }
        }

        void ingestStatement(String stmt) {
            if (stmt == null || stmt.isBlank()) {
                return;
            }
            try {
                List<CqlToken> t = new CqlLexer(stmt.strip()).tokenize();
                int fs = firstSignificant(t);
                if (fs < 0) {
                    return;
                }
                if (isKw(t, fs, "ALTER")) {
                    if (keywordSequenceAt(t, fs, "ALTER", "ROLE")) {
                        ingestRoleFromAlterRole(t, fs, this);
                        return;
                    }
                    if (keywordSequenceAt(t, fs, "ALTER", "USER")) {
                        ingestRoleFromAlterUser(t, fs, this);
                        return;
                    }
                    return;
                }
                if (isKw(t, fs, "DROP")) {
                    if (keywordSequenceAt(t, fs, "DROP", "ROLE")) {
                        String n = extractDropRoleOrUserName(t, fs, false);
                        if (n != null) {
                            rolesByName.remove(normalizeIdent(n));
                        }
                        return;
                    }
                    if (keywordSequenceAt(t, fs, "DROP", "USER")) {
                        String n = extractDropRoleOrUserName(t, fs, true);
                        if (n != null) {
                            rolesByName.remove(normalizeIdent(n));
                        }
                        return;
                    }
                    return;
                }
                if (!isKw(t, fs, "CREATE")) {
                    return;
                }
                if (keywordSequenceAt(t, fs, "CREATE", "TABLE")) {
                    TableSchema ts = parseCreateTableSchema(t, fs);
                    if (ts != null) {
                        tables.put(ts.tableKey, ts);
                    }
                } else if (keywordSequenceAt(t, fs, "CREATE", "MATERIALIZED", "VIEW")) {
                    String base = extractFromTableAfterAs(t);
                    if (base != null) {
                        mvByBase.merge(normalizeTableKey(base), 1, Integer::sum);
                    }
                } else if (keywordSequenceAt(t, fs, "CREATE", "OR", "REPLACE", "FUNCTION")
                        || keywordSequenceAt(t, fs, "CREATE", "FUNCTION")) {
                    String fn = extractFunctionQualifiedNameForRegistry(t, fs);
                    if (fn != null && !fn.isBlank()) {
                        registerFunction(fn);
                    }
                } else if (keywordSequenceAt(t, fs, "CREATE", "ROLE")
                        || keywordSequenceAt(t, fs, "CREATE", "OR", "REPLACE", "ROLE")) {
                    ingestRoleFromCreateRole(t, fs, this);
                } else if (keywordSequenceAt(t, fs, "CREATE", "USER")) {
                    ingestRoleFromCreateUser(t, fs, this);
                }
            } catch (CqlLexer.LexException ignored) {
            }
        }
    }
}
