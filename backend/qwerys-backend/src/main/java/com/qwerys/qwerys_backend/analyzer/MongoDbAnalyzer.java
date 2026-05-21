package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.analyzer.nosql.MongoJsParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural and safety-oriented analysis for MongoDB shell-style queries
 * ({@code db.<collection>.<method>(...)}).
 *
 * <p>Produces {@link SemanticError} instances compatible with the SQL semantic pipeline
 * ({@code ERROR} → {@link com.qwerys.qwerys_backend.model.AnalysisError} in the service layer).
 */
public final class MongoDbAnalyzer {

    private static final Pattern TRANSACTION_HINT = Pattern.compile(
            "(?i)\\b(startTransaction|withTransaction|commitTransaction|abortTransaction)\\s*\\(");

    private static final Pattern PAT_START_TX = Pattern.compile("\\.startTransaction\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_COMMIT_TX = Pattern.compile("\\.commitTransaction\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_ABORT_TX = Pattern.compile("\\.abortTransaction\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_WITH_TX = Pattern.compile("\\.withTransaction\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_DB_TX_SIDE_EFFECT = Pattern.compile(
            "\\bdb\\.\\w+\\.(insertOne|insertMany|updateOne|updateMany|replaceOne|replaceMany|deleteOne|deleteMany|bulkWrite)\\s*\\(",
            Pattern.CASE_INSENSITIVE);
    /** Matches maxCommitTimeMS / maxCommitTimeMs (MongoDB/driver spelling variants). */
    private static final Pattern PAT_MAX_COMMIT_ANY = Pattern.compile(
            "(?i)maxCommitTimeMs\\s*:\\s*(\\d+)");
    /** Driver callback option observed near transaction APIs (warn if unusually long ≥ 60s). */
    private static final Pattern PAT_TIMEOUT_MS = Pattern.compile(
            "(?<!\\w)timeoutMS\\s*:\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_TX_CONTEXT = Pattern.compile(
            "(?is)(\\.startTransaction|\\.withTransaction|\\.commitTransaction|\\.abortTransaction|startSession\\s*\\(|ClientSession)");

    private static final Pattern PAT_START_TX_BAD_FIRST_ARG = Pattern.compile(
            "\\.startTransaction\\s*\\(\\s*['\"]");

    private static final Pattern PAT_START_TX_ARRAY_ARG = Pattern.compile(
            "\\.startTransaction\\s*\\(\\s*\\[");

    private static final Pattern PAT_WATCH_CALL = Pattern.compile("\\.watch\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_WATCH_NO_PIPELINE =
            Pattern.compile(
                    "\\.watch\\s*\\(\\s*\\)"
                            + "|\\.watch\\s*\\(\\s*null\\s*\\)"
                            + "|\\.watch\\s*\\(\\s*undefined\\s*\\)"
                            + "|\\.watch\\s*\\(\\s*\\[\\s*\\]\\s*\\)"
                            + "|\\.watch\\s*\\(\\s*\\[\\s*\\]\\s*,",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_FULL_DOC_UPDATE_LOOKUP =
            Pattern.compile("fullDocument\\s*:\\s*['\"]updateLookup['\"]", Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_RESUME_AFTER_KEY = Pattern.compile("\\bresumeAfter\\s*:", Pattern.CASE_INSENSITIVE);

    private static final Pattern OBJECT_ID_HEX = Pattern.compile("^[a-fA-F0-9]{24}$");

    /** MongoDB text index languages commonly accepted (ISO + English names). */
    private static final Set<String> KNOWN_TEXT_LANGUAGES;

    static {
        HashSet<String> langs = new HashSet<>();
        String[] iso = {
                "none", "da", "nl", "en", "fi", "fr", "de", "hu", "it", "nb", "nn", "no", "pt", "ro", "ru", "es", "sv", "tr"
        };
        String[] names = {
                "danish", "dutch", "english", "finnish", "french", "german", "hungarian", "italian",
                "norwegian", "portuguese", "romanian", "russian", "spanish", "swedish", "turkish"
        };
        for (String s : iso) {
            langs.add(s.toLowerCase(Locale.ROOT));
        }
        for (String s : names) {
            langs.add(s.toLowerCase(Locale.ROOT));
        }
        KNOWN_TEXT_LANGUAGES = Collections.unmodifiableSet(langs);
    }

    private static String t(boolean es, String en, String esStr) {
        return es ? esStr : en;
    }

    /**
     * Lexes and analyzes {@code rawQuery}.
     *
     * @return findings (may be empty); never {@code null}
     */
    public List<SemanticError> analyze(String rawQuery) {
        return analyze(rawQuery, Locale.ENGLISH);
    }

    public List<SemanticError> analyze(String rawQuery, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        Locale loc = uiLocale != null ? uiLocale : Locale.ENGLISH;
        boolean es = loc.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        String script = rawQuery == null ? "" : rawQuery;
        if (script.isBlank()) {
            out.add(new SemanticError(
                    "MGO-SYNTAX-001",
                    es ? "La consulta MongoDB está vacía" : "MongoDB query is empty",
                    es ? "Escriba una llamada tipo shell, por ejemplo db.usuarios.find({ campo: \"valor\" })."
                            : "Write a shell-style call, e.g. db.users.find({ field: \"value\" }).",
                    SemanticError.Severity.ERROR));
            return out;
        }

        scanMongoTransactionsAndChangeStreams(script, out, es);

        boolean txHint = TRANSACTION_HINT.matcher(script).find();
        List<String> chunks = splitMongoShellStatements(script);

        for (String rawChunk : chunks) {
            String chunk = rawChunk.strip();
            if (chunk.isEmpty()) {
                continue;
            }
            if (!startsWithDbPrefix(chunk)) {
                if (looksLikeWrongEngineForMongo(chunk)) {
                    String sug = es
                            ? "Verifica que elegiste el motor correcto. Las consultas MongoDB deben empezar con db.nombreColeccion.método(...), por ejemplo: db.usuarios.find({ campo: 'valor' })"
                            : "Make sure you selected the correct database engine. MongoDB queries must start with db.collectionName.method(...), e.g. db.users.find({ field: 'value' })";
                    out.add(new SemanticError(
                            "MGO-WRONG-ENGINE",
                            es ? "Este input no parece ser sintaxis MongoDB. Las consultas MongoDB deben comenzar con db.nombreColeccion.método(...)"
                                    : "This input does not look like MongoDB syntax. MongoDB queries must start with db.collectionName.method(...)",
                            sug,
                            SemanticError.Severity.ERROR));
                }
                continue;
            }
            try {
                List<NoSqlToken> tokens = new MongoDbLexer(chunk, loc).tokenize();
                analyzeDbShellCallTokens(tokens, script, txHint, out, es, loc);
            } catch (MongoDbLexer.LexException le) {
                out.add(mapMongoLexException(le, es));
            }
        }
        applySequentialJsonSchemaAnalysis(script, out, loc);
        dedupeSemanticErrors(out);
        return out;
    }

    /**
     * Cross-statement / whole-script MongoDB checks only (transactions, change streams on full text).
     * Used by multi-statement orchestration so per-fragment analysis does not duplicate false TX-001, etc.
     */
    public List<SemanticError> analyzeCrossScriptOnly(String rawQuery, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        Locale loc = uiLocale != null ? uiLocale : Locale.ENGLISH;
        boolean es = loc.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        String script = rawQuery == null ? "" : rawQuery;
        if (script.isBlank()) {
            return out;
        }
        scanMongoTransactionsAndChangeStreams(script, out, es);
        dedupeSemanticErrors(out);
        return out;
    }

    /**
     * Analyzes a single statement fragment without whole-script transaction/change-stream heuristics.
     *
     * @param fullScriptForTxHint optional full script; when non-null, used for {@code $out}/$merge vs transaction hints
     */
    public List<SemanticError> analyzeFragment(String fragment, Locale uiLocale, String fullScriptForTxHint) {
        List<SemanticError> out = new ArrayList<>();
        Locale loc = uiLocale != null ? uiLocale : Locale.ENGLISH;
        boolean es = loc.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        String script = fragment == null ? "" : fragment;
        if (script.isBlank()) {
            return out;
        }

        String hintSource = fullScriptForTxHint != null && !fullScriptForTxHint.isBlank() ? fullScriptForTxHint : script;
        boolean txHint = TRANSACTION_HINT.matcher(hintSource).find();
        List<String> chunks = splitMongoShellStatements(script);

        for (String rawChunk : chunks) {
            String chunk = rawChunk.strip();
            if (chunk.isEmpty()) {
                continue;
            }
            if (!startsWithDbPrefix(chunk)) {
                if (looksLikeWrongEngineForMongo(chunk)) {
                    String sug = es
                            ? "Verifica que elegiste el motor correcto. Las consultas MongoDB deben empezar con db.nombreColeccion.método(...), por ejemplo: db.usuarios.find({ campo: 'valor' })"
                            : "Make sure you selected the correct database engine. MongoDB queries must start with db.collectionName.method(...), e.g. db.users.find({ field: 'value' })";
                    out.add(new SemanticError(
                            "MGO-WRONG-ENGINE",
                            es ? "Este input no parece ser sintaxis MongoDB. Las consultas MongoDB deben comenzar con db.nombreColeccion.método(...)"
                                    : "This input does not look like MongoDB syntax. MongoDB queries must start with db.collectionName.method(...)",
                            sug,
                            SemanticError.Severity.ERROR));
                }
                continue;
            }
            try {
                List<NoSqlToken> tokens = new MongoDbLexer(chunk, loc).tokenize();
                analyzeDbShellCallTokens(tokens, hintSource, txHint, out, es, loc);
            } catch (MongoDbLexer.LexException le) {
                out.add(mapMongoLexException(le, es));
            }
        }
        if (fullScriptForTxHint != null && !fullScriptForTxHint.isBlank()) {
            applyPriorJsonSchemaForFragment(fullScriptForTxHint, script, out, loc);
        }
        dedupeSemanticErrors(out);
        return out;
    }

    private static SemanticError mapMongoLexException(MongoDbLexer.LexException le, boolean es) {
        String msg = le.getMessage() != null ? le.getMessage() : "";
        boolean chainRelated = msg.contains("chained") || msg.contains("encadenad");
        if (chainRelated) {
            return new SemanticError(
                    "MGO-CHAIN-001",
                    msg,
                    t(es,
                            "Use métodos encadenados soportados: .limit(n), .skip(n), .sort({}), .project({}), .hint({}), etc.",
                            "Use supported chained methods: .limit(n), .skip(n), .sort({}), .project({}), .hint({}), etc."),
                    SemanticError.Severity.ERROR,
                    le.line(),
                    le.column());
        }
        return new SemanticError(
                "MGO-SYNTAX-001",
                msg,
                t(es,
                        "Revise db.coleccion.find({ ... }).limit(100) o insertOne/updateMany/aggregate(...). "
                                + "Use llaves para documentos y corchetes para pipelines.",
                        "Check db.collection.find({ ... }).limit(100) or insertOne/updateMany/aggregate(...). "
                                + "Use braces for documents and brackets for pipelines."),
                SemanticError.Severity.ERROR,
                le.line(),
                le.column());
    }

    private static void dedupeSemanticErrors(List<SemanticError> out) {
        Set<String> keys = new LinkedHashSet<>();
        Iterator<SemanticError> it = out.iterator();
        while (it.hasNext()) {
            SemanticError e = it.next();
            String key = e.code() + '|' + Objects.toString(e.line(), "");
            if (!keys.add(key)) {
                it.remove();
            }
        }
    }

    private enum ShellSplitState {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    /**
     * Splits mongo shell snippets on top-level ';' (not inside // line comments,
     * block comments, or single/double quoted strings).
     */
    private static List<String> splitMongoShellStatements(String input) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        ShellSplitState st = ShellSplitState.NORMAL;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (st) {
                case NORMAL -> {
                    if (c == '/' && i + 1 < input.length()) {
                        char n = input.charAt(i + 1);
                        if (n == '/') {
                            st = ShellSplitState.LINE_COMMENT;
                            cur.append(c);
                            cur.append(n);
                            i++;
                            continue;
                        }
                        if (n == '*') {
                            st = ShellSplitState.BLOCK_COMMENT;
                            cur.append(c);
                            cur.append(n);
                            i++;
                            continue;
                        }
                    }
                    if (c == '\'') {
                        st = ShellSplitState.SINGLE_QUOTE;
                        cur.append(c);
                        continue;
                    }
                    if (c == '\"') {
                        st = ShellSplitState.DOUBLE_QUOTE;
                        cur.append(c);
                        continue;
                    }
                    if (c == ';') {
                        out.add(cur.toString());
                        cur.setLength(0);
                        continue;
                    }
                    cur.append(c);
                }
                case SINGLE_QUOTE -> {
                    cur.append(c);
                    if (c == '\\' && i + 1 < input.length()) {
                        cur.append(input.charAt(i + 1));
                        i++;
                    } else if (c == '\'') {
                        st = ShellSplitState.NORMAL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    cur.append(c);
                    if (c == '\\' && i + 1 < input.length()) {
                        cur.append(input.charAt(i + 1));
                        i++;
                    } else if (c == '\"') {
                        st = ShellSplitState.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    cur.append(c);
                    if (c == '\r' || c == '\n') {
                        st = ShellSplitState.NORMAL;
                    }
                }
                case BLOCK_COMMENT -> {
                    cur.append(c);
                    if (c == '*' && i + 1 < input.length() && input.charAt(i + 1) == '/') {
                        cur.append('/');
                        i++;
                        st = ShellSplitState.NORMAL;
                    }
                }
                default -> cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static final Pattern PAT_DB_PREFIX = Pattern.compile("(?is)\\A\\s*db\\.");

    private static boolean startsWithDbPrefix(String chunk) {
        return chunk != null && PAT_DB_PREFIX.matcher(chunk).lookingAt();
    }

    /**
     * Returns true if the chunk clearly looks like syntax from a different engine,
     * allowing QWERYS to warn the user about wrong engine selection instead of
     * silently reporting "query is perfect".
     *
     * <p>Delegates to {@link CrossEngineSyntaxGuard} heuristics (same family as the service-level
     * guard) plus CQL-specific prefixes, so multi-statement Mongo fragments stay consistent with
     * single-statement analysis without duplicating keyword lists.
     */
    private static boolean looksLikeWrongEngineForMongo(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return false;
        }
        if (CrossEngineSyntaxGuard.containsRelationalSql(chunk)
                || CrossEngineSyntaxGuard.containsRedisCommandLine(chunk)
                || CrossEngineSyntaxGuard.looksLikeElasticsearchPayload(chunk)
                || CrossEngineSyntaxGuard.looksLikeDynamoDbApiJson(chunk)
                || CrossEngineSyntaxGuard.looksLikeDynamoSdkExpression(chunk)) {
            return true;
        }
        String upper = chunk.stripLeading().toUpperCase(Locale.ROOT);
        for (String prefix : CQL_WRONG_ENGINE_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final String[] CQL_WRONG_ENGINE_PREFIXES = {
            "BATCH ", "APPLY BATCH", "CONSISTENCY "
    };

    private static void analyzeDbShellCallTokens(List<NoSqlToken> tokens, String fullScript, boolean txHint,
            List<SemanticError> out, boolean es, Locale loc) {
        scanWhereAndFunction(tokens, out, es);
        scanEmbeddedMongoJs(tokens, out, loc);
        scanMapReduce(tokens, out, es);
        scanRegexIndexing(tokens, out, es);
        scanMissingFilters(tokens, out, es);
        scanAggregationRules(tokens, txHint, out, es);
        scanOperatorBodies(tokens, out, es);
        scanObjectIdStringComparisons(tokens, out, es);

        int wi = indexOfMethod(tokens, NoSqlTokenType.WATCH);
        if (wi >= 0) {
            int opts = findWatchOptionsBrace(tokens, skipLeadingCommas(tokens, wi + 1));
            if (opts >= 0) {
                validateChangeStreamOptionsObject(tokens, opts, out, es);
            }
        }
    }

    private static int findWatchOptionsBrace(List<NoSqlToken> tokens, int firstArgIdx) {
        if (firstArgIdx >= tokens.size()) {
            return -1;
        }
        NoSqlToken first = tokens.get(firstArgIdx);
        if (first.type() == NoSqlTokenType.BRACKET_OPEN) {
            int afterPipe = skipValue(tokens, firstArgIdx);
            int k = skipLeadingCommas(tokens, afterPipe);
            if (k < tokens.size() && tokens.get(k).type() == NoSqlTokenType.BRACE_OPEN) {
                return k;
            }
            return -1;
        }
        if (first.type() == NoSqlTokenType.BRACE_OPEN) {
            return firstArgIdx;
        }
        return -1;
    }

    private static void validateChangeStreamOptionsObject(List<NoSqlToken> tokens, int braceOpen, List<SemanticError> out,
            boolean es) {
        List<KeyVal> fields = parseTopLevelObjectPairs(tokens, braceOpen);
        for (KeyVal kv : fields) {
            if ("fullDocument".equals(kv.key())) {
                int vs = kv.valStart();
                if (vs < tokens.size() && tokens.get(vs).type() == NoSqlTokenType.STRING
                        && "updateLookup".equals(tokens.get(vs).value())
                        && !hasIssue(out, "MGO-CS-004")) {
                    int ln = tokens.get(braceOpen).line();
                    out.add(new SemanticError(
                            "MGO-CS-004",
                            t(es,
                                    "fullDocument updateLookup resolves full docs on updates — risky on huge collections",
                                    "fullDocument con updateLookup recupera documentos completos — arriesgado en colecciones muy grandes"),
                            t(es,
                                    "Prefer default or whenNeeded, or constrain with a projection/filter pipeline before heavy workloads.",
                                    "Prefiere default o whenNeeded, o acota con pipeline de filtro/proyección antes de cargas pesadas."),
                            SemanticError.Severity.WARNING,
                            ln,
                            null));
                }
            }
            if ("resumeAfter".equals(kv.key())) {
                int vs = kv.valStart();
                if (vs >= tokens.size()) {
                    continue;
                }
                if (tokens.get(vs).type() == NoSqlTokenType.NULL) {
                    out.add(cs003InvalidResumeToken(tokens.get(braceOpen).line(), es));
                    continue;
                }
                if (tokens.get(vs).type() == NoSqlTokenType.BRACE_OPEN) {
                    List<KeyVal> inner = parseTopLevelObjectPairs(tokens, vs);
                    if (inner.isEmpty()) {
                        out.add(cs003InvalidResumeToken(tokens.get(braceOpen).line(), es));
                        continue;
                    }
                    boolean hasIdKey = inner.stream().anyMatch(p -> "_id".equals(p.key()));
                    if (!hasIdKey) {
                        out.add(cs003InvalidResumeToken(tokens.get(braceOpen).line(), es));
                    }
                }
            }
        }
    }

    private static SemanticError cs003InvalidResumeToken(int line, boolean es) {
        return new SemanticError(
                "MGO-CS-003",
                t(es,
                        "resumeAfter requires a BSON document with data._id resume token fields",
                        "resumeAfter requiere un documento BSON válido del token (campo _id dentro de los datos esperados por el servidor)"),
                t(es,
                        "Use the opaque resume token captured from watch() iteration (getResumeToken()); do not invent values.",
                        "Usa el token opaco devuelto por el cursor (.getResumeToken() en drivers); no inventes valores."),
                SemanticError.Severity.ERROR,
                line,
                null);
    }

    private static void scanMongoTransactionsAndChangeStreams(String script, List<SemanticError> out, boolean es) {
        boolean start = PAT_START_TX.matcher(script).find();
        boolean commit = PAT_COMMIT_TX.matcher(script).find();
        boolean abort = PAT_ABORT_TX.matcher(script).find();
        if (start && !(commit || abort)) {
            Matcher sm = PAT_START_TX.matcher(script);
            if (sm.find()) {
                out.add(new SemanticError(
                        "MGO-TX-001",
                        t(es,
                                "startTransaction() without a visible commitTransaction() or abortTransaction()",
                                "startTransaction() sin commitTransaction() o abortTransaction() visibles"),
                        t(es,
                                "Ensure every started transaction commits or aborts (including async paths and error handlers).",
                                "Asegura que cada transacción iniciada haga commit o abort (incluye rutinas async y errores)."),
                        SemanticError.Severity.WARNING,
                        lineNumberAt(script, sm.start()),
                        null));
            }
        }

        if (start && PAT_DB_TX_SIDE_EFFECT.matcher(script).find() && !PAT_WITH_TX.matcher(script).find()) {
            Matcher sm2 = PAT_START_TX.matcher(script);
            Integer line = sm2.find() ? Integer.valueOf(lineNumberAt(script, sm2.start())) : null;
            out.add(new SemanticError(
                    "MGO-TX-002",
                    t(es,
                            "Writes/queries beside manual start/commit — prefer session.withTransaction() for correctness",
                            "Escrituras o lecturas fuera del patrón withTransaction(): considera session.withTransaction()"),
                    t(es,
                            "withTransaction retries commit on transient errors and centralizes rollback paths.",
                            "withTransaction() reintenta en errores transitorios y ordena mejor el rollback."),
                    SemanticError.Severity.INFO,
                    line,
                    null));
        }

        scanLongMongoTransactionTimers(script, out, es);

        if (PAT_START_TX_BAD_FIRST_ARG.matcher(script).find() || PAT_START_TX_ARRAY_ARG.matcher(script).find()) {
            Matcher bm = PAT_START_TX_BAD_FIRST_ARG.matcher(script);
            boolean found = bm.find();
            if (!found) {
                Matcher am = PAT_START_TX_ARRAY_ARG.matcher(script);
                found = am.find();
                bm = am;
            }
            if (found) {
                out.add(new SemanticError(
                        "MGO-TX-004",
                        t(es,
                                "startTransaction(options) expects an options document, not a bare string or JSON array literal",
                                "startTransaction(options) espera un documento de opciones, no una cadena o arreglo JSON"),
                        t(es,
                                "Pass an object literal like { readConcern: { level: 'local' }, maxCommitTimeMS: 9000 }.",
                                "Pasa un objeto como { readConcern: { level: 'local' }, maxCommitTimeMS: 9000 }."),
                        SemanticError.Severity.ERROR,
                        lineNumberAt(script, bm.start()),
                        null));
            }
        }

        Matcher wm = PAT_WATCH_CALL.matcher(script);
        while (wm.find()) {
            int lineCs = lineNumberAt(script, wm.start());
            out.add(new SemanticError(
                    "MGO-CS-001",
                    t(es,
                            "Change stream cursor is active — remember to close it",
                            "Stream de cambios activo: acuerda cerrar el cursor cuando termines"),
                    t(es,
                            "Iterate with drivers' cursor helpers / try-with-resources patterns to avoid orphaned streams.",
                            "Itera cerrando bien el cursor (try-with-resources, close explícito) para no dejar sesiones vivas."),
                    SemanticError.Severity.INFO,
                    lineCs,
                    null));

            int localStart = wm.start();
            String tail = script.substring(localStart, Math.min(script.length(), localStart + 512));
            if (PAT_WATCH_NO_PIPELINE.matcher(tail).find()) {
                out.add(new SemanticError(
                        "MGO-CS-002",
                        t(es,
                                "watch() without a pipeline filter",
                                "watch() sin pipeline de filtro"),
                        t(es,
                                "Prefer [{ $match: { ... }}] early to shrink network + server work unless you truly need everything.",
                                "Prefer [{ $match: { ... }}] al inicio para reducir tráfico y carga si no necesitas todos los eventos."),
                        SemanticError.Severity.INFO,
                        lineCs,
                        null));
            }
        }

        scanResumeAfterInScript(script, out, es);

        Matcher fd = PAT_FULL_DOC_UPDATE_LOOKUP.matcher(script);
        if (fd.find() && !hasIssue(out, "MGO-CS-004")) {
            out.add(new SemanticError(
                    "MGO-CS-004",
                    t(es,
                            "fullDocument updateLookup resolves full docs on updates — risky on huge collections",
                            "fullDocument con updateLookup recupera documentos completos — arriesgado en colecciones muy grandes"),
                    t(es,
                            "Prefer default or whenNeeded, or constrain projection first.",
                            "Prefiere default o whenNeeded o acota antes con proyección/filtros."),
                    SemanticError.Severity.WARNING,
                    lineNumberAt(script, fd.start()),
                    null));
        }
    }

    private static void scanResumeAfterInScript(String script, List<SemanticError> out, boolean es) {
        Matcher am = PAT_RESUME_AFTER_KEY.matcher(script);
        while (am.find()) {
            int idx = skipWs(script, am.end());
            if (idx >= script.length()) {
                continue;
            }
            if (regionMatchesInsensitive(script, idx, "null")) {
                out.add(new SemanticError(
                        "MGO-CS-003",
                        t(es,
                                "resumeAfter cannot be null",
                                "resumeAfter no puede ser null"),
                        t(es,
                                "Use the opaque resume token from the previous change stream iteration.",
                                "Usa el token opaco devuelto por la iteración previa del watch()."),
                        SemanticError.Severity.ERROR,
                        lineNumberAt(script, am.start()),
                        null));
                continue;
            }
            char ch = script.charAt(idx);
            if (ch == '{') {
                Integer endExclusive = balancedBraceEndExclusive(script, idx);
                if (endExclusive == null) {
                    continue;
                }
                String inner = script.substring(idx + 1, endExclusive - 1).strip();
                if (inner.isEmpty()) {
                    out.add(cs003Inline(script, am.start(), es));
                    continue;
                }
                boolean hasResumeIdPattern = Pattern.compile("(?is)[\"']?_id[\"']?\\s*:").matcher(inner).find();
                if (!hasResumeIdPattern) {
                    out.add(cs003Inline(script, am.start(), es));
                }
            }
        }
    }

    private static SemanticError cs003Inline(String script, int anchor, boolean es) {
        return new SemanticError(
                "MGO-CS-003",
                t(es,
                        "resumeAfter requires a valid BSON resume token (includes _id shaped per driver/server docs)",
                        "resumeAfter requiere un token BSON válido (incluye _id según formato del servidor/driver)"),
                t(es,
                        "Pass the opaque document from ChangeStream.resumeToken/getResumeToken() — do not synthesize payloads.",
                        "Pasa el documento opaco de resumeToken()/getResumeToken(); no sintetices carga manualmente."),
                SemanticError.Severity.ERROR,
                lineNumberAt(script, anchor),
                null);
    }

    private static Integer balancedBraceEndExclusive(String s, int openIdx) {
        if (openIdx >= s.length() || s.charAt(openIdx) != '{') {
            return null;
        }
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return null;
    }

    private static int skipWs(String s, int idx) {
        while (idx < s.length() && Character.isWhitespace(s.charAt(idx))) {
            idx++;
        }
        return idx;
    }

    private static boolean regionMatchesInsensitive(String s, int idx, String word) {
        return s.regionMatches(true, idx, word, 0, word.length());
    }

    private static boolean hasIssue(List<SemanticError> out, String code) {
        return out.stream().anyMatch(e -> code.equals(e.code()));
    }

    private static int lineNumberAt(String script, int charIdx) {
        int ln = 1;
        int end = Math.min(charIdx, script.length());
        for (int i = 0; i < end; i++) {
            if (script.charAt(i) == '\n') {
                ln++;
            }
        }
        return ln;
    }

    private static void scanLongMongoTransactionTimers(String script, List<SemanticError> out, boolean es) {
        Matcher m = PAT_MAX_COMMIT_ANY.matcher(script);
        while (m.find()) {
            try {
                long msValue = Long.parseLong(m.group(1));
                if (msValue < 60_000L || hasIssue(out, "MGO-TX-003")) {
                    continue;
                }
                warnTx003(script, m.start(), es, out);
                return;
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        Matcher tm = PAT_TIMEOUT_MS.matcher(script);
        while (tm.find()) {
            if (hasIssue(out, "MGO-TX-003")) {
                return;
            }
            try {
                long ms = Long.parseLong(tm.group(1));
                if (ms < 60_000L) {
                    continue;
                }
                String win = substringWindow(script, tm.start(), 220);
                if (PAT_TX_CONTEXT.matcher(win).find()) {
                    warnTx003(script, tm.start(), es, out);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
    }

    private static String substringWindow(String s, int center, int width) {
        int half = width / 2;
        int a = Math.max(0, center - half);
        int b = Math.min(s.length(), center + half);
        return s.substring(a, b);
    }

    private static void warnTx003(String script, int start, boolean es, List<SemanticError> out) {
        out.add(new SemanticError(
                "MGO-TX-003",
                t(es,
                        "MongoDB aborts long-running transactions (default ~60s) — shorten this window",
                        "MongoDB suele abortar transacciones largas (orden de ~60s) — reduce la ventana"),
                t(es,
                        "Shrink maxCommitTimeMS / transaction scope, prefetch less work per transaction, avoid blocking awaits.",
                        "Reduce maxCommitTimeMS y el trabajo por transacción; evita esperas bloqueantes largas dentro del mismo attempt."),
                SemanticError.Severity.WARNING,
                lineNumberAt(script, start),
                null));
    }

    private static void scanWhereAndFunction(List<NoSqlToken> tokens, List<SemanticError> out, boolean es) {
        for (NoSqlToken t : tokens) {
            if (t.type() != NoSqlTokenType.OPERATOR) {
                continue;
            }
            if ("$where".equals(t.value())) {
                out.add(new SemanticError(
                        "MGO-JS-001",
                        t(es,
                                "Avoid $where: runs JavaScript on the server — security risk and performance bottleneck",
                                "Evite $where: ejecuta JavaScript en el servidor — riesgo de seguridad y cuello de rendimiento"),
                        t(es,
                                "Replace $where with query operators ($eq, $in, $expr) or an aggregation pipeline.",
                                "Reemplace $where por operadores de consulta ($eq, $in, $expr) o una canalización de agregación."),
                        SemanticError.Severity.ERROR));
            } else if ("$function".equals(t.value())) {
                out.add(new SemanticError(
                        "MGO-JS-002",
                        t(es, "$function runs JavaScript; prefer aggregation operators when possible", "$function ejecuta JavaScript; prefiera operadores de agregación cuando sea posible"),
                        t(es,
                                "Prefer native aggregation stages and operators; isolate JS to audited, bounded workloads.",
                                "Prefiera etapas y operadores nativos; aísle JS a cargas acotadas y auditadas."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private static void scanMapReduce(List<NoSqlToken> tokens, List<SemanticError> out, boolean es) {
        for (NoSqlToken t : tokens) {
            if (t.type() == NoSqlTokenType.MAPREDUCE) {
                out.add(new SemanticError(
                        "MGO-JS-003",
                        t(es,
                                "mapReduce is deprecated in MongoDB 5.0+; use the aggregation pipeline",
                                "mapReduce está obsoleto en MongoDB 5.0+; use la canalización de agregación"),
                        t(es,
                                "Rewrite as db.collection.aggregate([...]) with $group, $merge, or $out as needed.",
                                "Reescriba como db.collection.aggregate([...]) con $group, $merge o $out según necesidad."),
                        SemanticError.Severity.WARNING));
                break;
            }
        }
    }

    private static void scanRegexIndexing(List<NoSqlToken> tokens, List<SemanticError> out, boolean es) {
        boolean regexSeen = false;
        for (NoSqlToken t : tokens) {
            if (t.type() == NoSqlTokenType.OPERATOR && "$regex".equals(t.value())) {
                regexSeen = true;
                break;
            }
            if (t.type() == NoSqlTokenType.STRING && t.value().startsWith("/")) {
                regexSeen = true;
                break;
            }
        }
        if (!regexSeen) {
            return;
        }
        out.add(new SemanticError(
                "MGO-REGEX-001",
                t(es,
                        "Query uses $regex or a /.../ literal (regular expression)",
                        "Consulta con $regex o literal /.../ (expresión regular)"),
                t(es,
                        "Regex matches often skip B-tree indexes except prefix-anchored patterns "
                                + "(e.g. ^literal); consider a text index, a normalized search field, "
                                + "or a pipeline that narrows the document set first.",
                        "Las coincidencias por regex suelen no usar índices B-tree salvo patrones anclados por prefijo "
                                + "(p.ej. ^literal); evalúe un índice de texto, un campo normalizado para búsqueda, "
                                + "o una canalización que restrinja antes el conjunto de documentos."),
                SemanticError.Severity.WARNING));
    }

    private static void scanMissingFilters(List<NoSqlToken> tokens, List<SemanticError> out, boolean es) {
        for (int i = 0; i < tokens.size(); i++) {
            NoSqlTokenType ty = tokens.get(i).type();
            if (ty != NoSqlTokenType.FIND && ty != NoSqlTokenType.UPDATE && ty != NoSqlTokenType.DELETE) {
                continue;
            }

            int argStart = skipLeadingCommas(tokens, i + 1);
            if (argStart >= tokens.size()) {
                continue;
            }

            NoSqlToken first = tokens.get(argStart);
            if (first.type() == NoSqlTokenType.EOF || isEmptyDocument(tokens, argStart)) {
                out.add(new SemanticError(
                        "MGO-NOFILTER-001",
                        t(es,
                                label(ty) + " with no filter (empty document or no arguments)",
                                label(ty) + " sin filtro (documento vacío o sin argumentos)"),
                        t(es,
                                "Without a restrictive filter the operation may apply to every document in the collection. "
                                        + "Add an explicit predicate or confirm you intend to process the whole collection.",
                                "Sin un filtro restrictivo la operación puede aplicarse a todos los documentos de la colección. "
                                        + "Añada un criterio explícito o confirme que desea procesar la colección completa."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private static String label(NoSqlTokenType ty) {
        return switch (ty) {
            case FIND -> "find/findOne";
            case UPDATE -> "update/replace";
            case DELETE -> "delete/remove";
            default -> ty.name().toLowerCase(Locale.ROOT);
        };
    }

    private static int skipLeadingCommas(List<NoSqlToken> tokens, int idx) {
        int k = idx;
        while (k < tokens.size() && tokens.get(k).type() == NoSqlTokenType.COMMA) {
            k++;
        }
        return k;
    }

    private static boolean isEmptyDocument(List<NoSqlToken> tokens, int braceIdx) {
        if (braceIdx + 1 >= tokens.size()) {
            return false;
        }
        return tokens.get(braceIdx).type() == NoSqlTokenType.BRACE_OPEN
                && tokens.get(braceIdx + 1).type() == NoSqlTokenType.BRACE_CLOSE;
    }

    // --- Aggregation pipeline & cross-cutting operators ---

    private static void scanAggregationRules(List<NoSqlToken> tokens, boolean txHint, List<SemanticError> out, boolean es) {
        int aggIdx = indexOfMethod(tokens, NoSqlTokenType.AGGREGATE);
        if (aggIdx < 0) {
            return;
        }
        int pStart = firstPipelineBracket(tokens, aggIdx);
        if (pStart < 0) {
            return;
        }

        List<Integer> stageOpens = collectStageBraceOpens(tokens, pStart);
        if (stageOpens.isEmpty()) {
            return;
        }

        String firstStageOp = firstStageOperator(tokens, stageOpens.get(0));
        if (stageOpens.size() > 5 && !"$match".equals(firstStageOp)) {
            out.add(new SemanticError(
                    "MGO-AGG-PERF-001",
                    t(es,
                            "Add $match early in the pipeline to reduce documents processed",
                            "Añada $match al inicio de la canalización para reducir documentos procesados"),
                    t(es,
                            "Place a selective $match stage first so later stages scan fewer documents.",
                            "Coloque primero un $match selectivo para que las etapas posteriores escaneen menos documentos."),
                    SemanticError.Severity.WARNING));
        }

        if (txHint && containsStageOperators(tokens, stageOpens, "$out", "$merge")) {
            out.add(new SemanticError(
                    "MGO-TX-OUT-001",
                    t(es, "$out/$merge cannot be used inside transactions", "$out/$merge no se pueden usar dentro de transacciones"),
                    t(es,
                            "Run $merge/$out outside multi-document transactions or use a separate session.",
                            "Ejecute $merge/$out fuera de transacciones multi-documento o use otra sesión."),
                    SemanticError.Severity.WARNING));
        }

        for (int stageOpen : stageOpens) {
            List<KeyVal> top = parseTopLevelObjectPairs(tokens, stageOpen);
            for (KeyVal kv : top) {
                scanStageContents(tokens, kv, out, es);
            }
        }
    }

    private static void scanStageContents(List<NoSqlToken> tokens, KeyVal stageKv, List<SemanticError> out, boolean es) {
        String op = stageKv.key();
        if (!op.startsWith("$")) {
            return;
        }
        int vs = stageKv.valStart();

        switch (op) {
            case "$lookup" -> validateLookup(tokens, vs, out, es);
            case "$unwind" -> validateUnwind(tokens, vs, out, es);
            case "$group" -> validateGroup(tokens, vs, out, es);
            case "$facet" -> validateFacet(tokens, vs, out, es);
            case "$graphLookup" -> validateGraphLookup(tokens, vs, out, es);
            default -> {
            }
        }
    }

    private static void validateLookup(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart >= tokens.size() || tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return;
        }
        List<KeyVal> inner = parseTopLevelObjectPairs(tokens, valStart);
        boolean hasLf = false;
        boolean hasFf = false;
        boolean hasPipeline = false;
        for (KeyVal kv : inner) {
            if ("localField".equals(kv.key())) {
                hasLf = true;
            }
            if ("foreignField".equals(kv.key())) {
                hasFf = true;
            }
            if ("pipeline".equals(kv.key())) {
                hasPipeline = true;
            }
        }
        if (!(hasLf && hasFf) && !hasPipeline) {
            out.add(new SemanticError(
                    "MGO-LOOKUP-001",
                    t(es,
                            "$lookup requires either (localField+foreignField) or pipeline syntax",
                            "$lookup requiere (localField+foreignField) o sintaxis con pipeline"),
                    t(es,
                            "Use localField/foreignField + from/as, or define pipeline + let syntax per MongoDB docs.",
                            "Use localField/foreignField + from/as, o pipeline + let según la documentación de MongoDB."),
                    SemanticError.Severity.ERROR));
        }
    }

    private static void validateUnwind(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart >= tokens.size()) {
            return;
        }
        if (tokens.get(valStart).type() == NoSqlTokenType.STRING) {
            String path = tokens.get(valStart).value();
            if (!path.startsWith("$")) {
                out.add(new SemanticError(
                        "MGO-UNWIND-001",
                        t(es,
                                "Unwind path must start with $ — use '$fieldName'",
                                "La ruta de $unwind debe empezar por $ — use '$nombreCampo'"),
                        t(es,
                                "Prefix the field path with $, e.g. \"$user.tags\".",
                                "Prefije la ruta con $, p.ej. \"$user.tags\"."),
                        SemanticError.Severity.ERROR));
            }
            return;
        }
        if (tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return;
        }
        List<KeyVal> inner = parseTopLevelObjectPairs(tokens, valStart);
        for (KeyVal kv : inner) {
            if ("path".equals(kv.key())) {
                int ps = kv.valStart();
                if (ps < tokens.size() && tokens.get(ps).type() == NoSqlTokenType.STRING) {
                    String path = tokens.get(ps).value();
                    if (!path.startsWith("$")) {
                        out.add(new SemanticError(
                                "MGO-UNWIND-001",
                                t(es,
                                        "Unwind path must start with $ — use '$fieldName'",
                                        "La ruta de $unwind debe empezar por $ — use '$nombreCampo'"),
                                t(es,
                                        "Set path to a field reference beginning with $.",
                                        "Establezca path como referencia a campo que empiece por $."),
                                SemanticError.Severity.ERROR));
                    }
                }
            }
        }
    }

    private static void validateGroup(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart >= tokens.size() || tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return;
        }
        List<KeyVal> inner = parseTopLevelObjectPairs(tokens, valStart);
        boolean hasId = false;
        for (KeyVal kv : inner) {
            if ("_id".equals(kv.key())) {
                hasId = true;
                break;
            }
        }
        if (!hasId) {
            out.add(new SemanticError(
                    "MGO-GROUP-001",
                    t(es,
                            "$group requires an _id field (use null to group all documents together)",
                            "$group requiere campo _id (use null para agrupar todos los documentos)"),
                    t(es,
                            "Add _id: null or _id: \"$field\" (and accumulators) to the $group document.",
                            "Añada _id: null o _id: \"$campo\" (y acumuladores) al documento $group."),
                    SemanticError.Severity.ERROR));
        }
    }

    private static void validateFacet(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart >= tokens.size() || tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return;
        }
        List<KeyVal> fields = parseTopLevelObjectPairs(tokens, valStart);
        for (KeyVal kv : fields) {
            int fs = kv.valStart();
            if (fs < tokens.size() && tokens.get(fs).type() == NoSqlTokenType.BRACKET_OPEN) {
                if (pipelineArrayContainsForbiddenStages(tokens, fs)) {
                    out.add(new SemanticError(
                            "MGO-FACET-001",
                            t(es, "These stages cannot be used inside $facet", "Estas etapas no pueden usarse dentro de $facet"),
                            t(es,
                                    "Remove $out, $merge, or $indexStats from sub-pipelines within $facet.",
                                    "Elimine $out, $merge o $indexStats de las sub-canalizaciones dentro de $facet."),
                            SemanticError.Severity.ERROR));
                }
            }
        }
    }

    private static void validateGraphLookup(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart >= tokens.size() || tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return;
        }
        List<KeyVal> inner = parseTopLevelObjectPairs(tokens, valStart);
        for (KeyVal kv : inner) {
            if ("maxDepth".equals(kv.key())) {
                int ms = kv.valStart();
                if (ms < tokens.size() && tokens.get(ms).type() == NoSqlTokenType.NUMBER) {
                    try {
                        double d = Double.parseDouble(tokens.get(ms).value());
                        if (d > 10.0) {
                            out.add(new SemanticError(
                                    "MGO-GRAPH-001",
                                    t(es,
                                            "Very deep $graphLookup may hurt performance",
                                            "$graphLookup muy profundo puede afectar el rendimiento"),
                                    t(es,
                                            "Lower maxDepth or narrow the graph with additional filters.",
                                            "Reduzca maxDepth o acote el grafo con filtros adicionales."),
                                    SemanticError.Severity.WARNING));
                        }
                    } catch (NumberFormatException ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    private static void scanOperatorBodies(List<NoSqlToken> tokens, List<SemanticError> out, boolean es) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type() != NoSqlTokenType.OPERATOR) {
                continue;
            }
            String op = tokens.get(i).value();
            if (i + 1 >= tokens.size() || tokens.get(i + 1).type() != NoSqlTokenType.COLON) {
                continue;
            }
            int valStart = i + 2;
            int valEnd = skipValue(tokens, valStart);

            switch (op) {
                case "$elemMatch" -> validateElemMatch(tokens, valStart, out, es);
                case "$push", "$addToSet" -> validatePushStyleArrays(tokens, valStart, out, es);
                case "$pull" -> validatePull(tokens, valStart, out, es);
                case "$text" -> out.add(new SemanticError(
                        "MGO-TEXT-001",
                        t(es,
                                "Text search needs a text index; e.g. db.collection.createIndex({field: 'text'})",
                                "La búsqueda de texto requiere índice de texto; p.ej. db.collection.createIndex({campo: 'text'})"),
                        t(es,
                                "Create a text index on searched fields and keep $search clauses selective.",
                                "Cree un índice de texto en los campos buscados y mantenga $search selectivo."),
                        SemanticError.Severity.WARNING));
                case "$language" -> validateTextLanguage(tokens, valStart, out, es);
                case "$near", "$nearSphere" -> {
                    out.add(new SemanticError(
                            "MGO-GEO-IDX-001",
                            t(es,
                                    "Geo queries need a 2dsphere or 2d index",
                                    "Las consultas geo requieren índice 2dsphere o 2d"),
                            t(es,
                                    "Ensure the geo field has an appropriate index before deploying near queries.",
                                    "Asegure un índice adecuado en el campo geo antes de usar consultas near."),
                            SemanticError.Severity.WARNING));
                    validateNearMaxDistance(tokens, valStart, out, es);
                }
                case "$geoWithin", "$geoIntersects" -> {
                    out.add(new SemanticError(
                            "MGO-GEO-IDX-001",
                            t(es,
                                    "Geo queries need a 2dsphere or 2d index",
                                    "Las consultas geo requieren índice 2dsphere o 2d"),
                            t(es,
                                    "Index geo fields with 2dsphere (recommended) or legacy 2d as applicable.",
                                    "Indexe campos geo con 2dsphere (recomendado) o 2d heredado según aplique."),
                            SemanticError.Severity.WARNING));
                    if ("$geoWithin".equals(op)) {
                        validateGeoWithinPolygon(tokens, valStart, valEnd, out, es);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void validateElemMatch(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart < tokens.size() && tokens.get(valStart).type() == NoSqlTokenType.BRACE_OPEN) {
            List<KeyVal> inner = parseTopLevelObjectPairs(tokens, valStart);
            if (inner.isEmpty()) {
                out.add(new SemanticError(
                        "MGO-ELEM-001",
                        t(es, "$elemMatch needs at least one condition", "$elemMatch requiere al menos una condición"),
                        t(es,
                                "Add predicates inside $elemMatch, e.g. { score: { $gt: 80 } }.",
                                "Añada predicados dentro de $elemMatch, p.ej. { score: { $gt: 80 } }."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private static void validatePushStyleArrays(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart >= tokens.size() || tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return;
        }
        List<KeyVal> fields = parseTopLevelObjectPairs(tokens, valStart);
        for (KeyVal kv : fields) {
            int fs = kv.valStart();
            if (fs < tokens.size() && tokens.get(fs).type() == NoSqlTokenType.BRACKET_OPEN) {
                if (countTopLevelArrayElements(tokens, fs) > 1 && !arrayUsesEachModifier(tokens, fs)) {
                    out.add(new SemanticError(
                            "MGO-PUSH-001",
                            t(es, "Use $each to push multiple values at once", "Use $each para insertar varios valores a la vez"),
                            t(es,
                                    "Wrap multiple values with { $each: [ ... ] } for $push/$addToSet.",
                                    "Envuelva varios valores con { $each: [ ... ] } en $push/$addToSet."),
                            SemanticError.Severity.INFO));
                }
            }
        }
    }

    private static boolean arrayUsesEachModifier(List<NoSqlToken> tokens, int bracketOpen) {
        int i = bracketOpen + 1;
        while (i < tokens.size() && tokens.get(i).type() != NoSqlTokenType.BRACKET_CLOSE) {
            if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            if (tokens.get(i).type() == NoSqlTokenType.BRACE_OPEN) {
                List<KeyVal> ps = parseTopLevelObjectPairs(tokens, i);
                for (KeyVal p : ps) {
                    if ("$each".equals(p.key())) {
                        return true;
                    }
                }
            }
            i = skipValue(tokens, i);
        }
        return false;
    }

    private static void validatePull(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart < tokens.size() && tokens.get(valStart).type() == NoSqlTokenType.BRACE_OPEN) {
            List<KeyVal> inner = parseTopLevelObjectPairs(tokens, valStart);
            if (inner.isEmpty()) {
                out.add(new SemanticError(
                        "MGO-PULL-001",
                        t(es,
                                "Empty $pull condition removes all matching array elements",
                                "$pull vacío elimina todos los elementos coincidentes del arreglo"),
                        t(es,
                                "Restrict $pull with explicit predicates to avoid deleting entire array contents unintentionally.",
                                "Restrinja $pull con predicados explícitos para no borrar todo el arreglo sin querer."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private static void validateTextLanguage(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart < tokens.size() && tokens.get(valStart).type() == NoSqlTokenType.STRING) {
            String lang = tokens.get(valStart).value().toLowerCase(Locale.ROOT);
            if (!KNOWN_TEXT_LANGUAGES.contains(lang)) {
                out.add(new SemanticError(
                        "MGO-TEXT-LANG-001",
                        t(es, "Unsupported language — check MongoDB docs", "Idioma no admitido — revise la documentación de MongoDB"),
                        t(es,
                                "Use a supported text language code/name or omit $language to use the index default.",
                                "Use un código/nombre admitido u omita $language para el valor por defecto del índice."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private static void validateNearMaxDistance(List<NoSqlToken> tokens, int valStart, List<SemanticError> out, boolean es) {
        if (valStart >= tokens.size()) {
            return;
        }
        if (tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return;
        }
        List<KeyVal> inner = parseTopLevelObjectPairs(tokens, valStart);
        for (KeyVal kv : inner) {
            if ("$maxDistance".equals(kv.key())) {
                return;
            }
        }
        out.add(new SemanticError(
                "MGO-GEO-NEAR-001",
                t(es,
                        "Add $maxDistance to bound results and improve performance",
                        "Añada $maxDistance para acotar resultados y mejorar el rendimiento"),
                t(es,
                        "Specify $maxDistance (and optionally $minDistance) to bound geo scans.",
                        "Indique $maxDistance (y opcionalmente $minDistance) para acotar escaneos geo."),
                SemanticError.Severity.WARNING));
    }

    private static void validateGeoWithinPolygon(List<NoSqlToken> tokens, int valStart, int valEndExclusive,
            List<SemanticError> out, boolean es) {
        Integer ringOpen = findPolygonOuterRingBracket(tokens, valStart, valEndExclusive);
        if (ringOpen == null) {
            return;
        }
        if (!isClosedLinearRing(tokens, ringOpen)) {
            out.add(new SemanticError(
                    "MGO-GEO-POLY-001",
                    t(es,
                            "GeoJSON polygon must be closed (first and last coordinates must match)",
                            "El polígono GeoJSON debe cerrarse (primera y última coordenada iguales)"),
                    t(es,
                            "Repeat the first coordinate at the end of each linear ring.",
                            "Repita la primera coordenada al final de cada anillo lineal."),
                    SemanticError.Severity.ERROR));
        }
    }

    /**
     * Finds the first linear ring array inside a $geoWithin payload (best-effort over token ranges).
     */
    private static Integer findPolygonOuterRingBracket(List<NoSqlToken> tokens, int start, int endExclusive) {
        for (int i = start; i < endExclusive && i < tokens.size(); i++) {
            if (tokens.get(i).type() != NoSqlTokenType.OPERATOR || !"$polygon".equals(tokens.get(i).value())) {
                continue;
            }
            if (i + 2 >= tokens.size() || tokens.get(i + 1).type() != NoSqlTokenType.COLON) {
                continue;
            }
            int v = i + 2;
            if (v < tokens.size() && tokens.get(v).type() == NoSqlTokenType.BRACKET_OPEN) {
                int inner = v + 1;
                if (inner < tokens.size() && tokens.get(inner).type() == NoSqlTokenType.BRACKET_OPEN) {
                    return inner;
                }
                return v;
            }
        }
        for (int i = start; i < endExclusive && i < tokens.size(); i++) {
            if ("coordinates".equals(tokens.get(i).value())
                    && tokens.get(i).type() == NoSqlTokenType.FIELD_NAME
                    && i + 2 < tokens.size()
                    && tokens.get(i + 1).type() == NoSqlTokenType.COLON) {
                int v = i + 2;
                if (v < tokens.size() && tokens.get(v).type() == NoSqlTokenType.BRACKET_OPEN) {
                    int j = v + 1;
                    if (j < tokens.size() && tokens.get(j).type() == NoSqlTokenType.BRACKET_OPEN) {
                        int k = j + 1;
                        if (k < tokens.size() && tokens.get(k).type() == NoSqlTokenType.BRACKET_OPEN) {
                            return k;
                        }
                        return j;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isClosedLinearRing(List<NoSqlToken> tokens, int ringBracketOpen) {
        List<String> points = new ArrayList<>();
        int i = ringBracketOpen + 1;
        while (i < tokens.size() && tokens.get(i).type() != NoSqlTokenType.BRACKET_CLOSE) {
            if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            if (tokens.get(i).type() == NoSqlTokenType.BRACKET_OPEN) {
                Double[] pair = readLngLatPair(tokens, i);
                if (pair != null) {
                    points.add(pair[0] + ":" + pair[1]);
                }
                i = skipBalanced(tokens, i, NoSqlTokenType.BRACKET_OPEN, NoSqlTokenType.BRACKET_CLOSE);
            } else {
                i++;
            }
        }
        if (points.size() < 4) {
            return true;
        }
        return points.get(0).equals(points.get(points.size() - 1));
    }

    private static Double[] readLngLatPair(List<NoSqlToken> tokens, int pairOpen) {
        int j = pairOpen + 1;
        Double lng = null;
        Double lat = null;
        int nums = 0;
        while (j < tokens.size() && tokens.get(j).type() != NoSqlTokenType.BRACKET_CLOSE) {
            if (tokens.get(j).type() == NoSqlTokenType.COMMA) {
                j++;
                continue;
            }
            if (tokens.get(j).type() == NoSqlTokenType.NUMBER && nums < 2) {
                try {
                    double d = Double.parseDouble(tokens.get(j).value());
                    if (nums == 0) {
                        lng = d;
                    } else {
                        lat = d;
                    }
                    nums++;
                } catch (NumberFormatException ignored) {
                    return null;
                }
                j++;
            } else {
                return null;
            }
        }
        if (lng == null || lat == null) {
            return null;
        }
        return new Double[]{lng, lat};
    }

    private static void scanObjectIdStringComparisons(List<NoSqlToken> tokens, List<SemanticError> out, boolean es) {
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).type() != NoSqlTokenType.FIELD_NAME || !"_id".equals(tokens.get(i).value())) {
                continue;
            }
            if (tokens.get(i + 1).type() != NoSqlTokenType.COLON) {
                continue;
            }
            NoSqlToken val = tokens.get(i + 2);
            if (val.type() == NoSqlTokenType.STRING && OBJECT_ID_HEX.matcher(val.value()).matches()) {
                out.add(new SemanticError(
                        "MGO-OID-001",
                        t(es,
                                "Comparing a string to ObjectId will always fail — use ObjectId('...')",
                                "Comparar cadena con ObjectId siempre fallará — use ObjectId('...')"),
                        t(es,
                                "Use ObjectId(\"...\") or query with the proper BSON type instead of a bare string.",
                                "Use ObjectId(\"...\") o consulte con el tipo BSON correcto, no solo una cadena."),
                        SemanticError.Severity.INFO));
            }
        }
    }

    // --- Token navigation helpers ---

    private record KeyVal(String key, int valStart, int valEnd) {}

    private static List<KeyVal> parseTopLevelObjectPairs(List<NoSqlToken> tokens, int braceOpenIdx) {
        List<KeyVal> pairs = new ArrayList<>();
        int i = braceOpenIdx + 1;
        if (i >= tokens.size()) {
            return pairs;
        }
        if (tokens.get(i).type() == NoSqlTokenType.BRACE_CLOSE) {
            return pairs;
        }
        while (i < tokens.size()) {
            if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            if (tokens.get(i).type() != NoSqlTokenType.FIELD_NAME && tokens.get(i).type() != NoSqlTokenType.OPERATOR) {
                break;
            }
            String key = tokens.get(i).value();
            i++;
            if (i >= tokens.size() || tokens.get(i).type() != NoSqlTokenType.COLON) {
                break;
            }
            i++;
            int valStart = i;
            i = skipValue(tokens, valStart);
            pairs.add(new KeyVal(key, valStart, i));
            if (i < tokens.size() && tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            break;
        }
        return pairs;
    }

    private static int skipValue(List<NoSqlToken> tokens, int start) {
        if (start >= tokens.size()) {
            return start;
        }
        NoSqlTokenType t = tokens.get(start).type();
        if (t == NoSqlTokenType.BRACE_OPEN) {
            return skipBalanced(tokens, start, NoSqlTokenType.BRACE_OPEN, NoSqlTokenType.BRACE_CLOSE);
        }
        if (t == NoSqlTokenType.BRACKET_OPEN) {
            return skipBalanced(tokens, start, NoSqlTokenType.BRACKET_OPEN, NoSqlTokenType.BRACKET_CLOSE);
        }
        return start + 1;
    }

    private static int skipBalanced(List<NoSqlToken> tokens, int openIdx,
            NoSqlTokenType open, NoSqlTokenType close) {
        int depth = 0;
        for (int j = openIdx; j < tokens.size(); j++) {
            NoSqlTokenType ty = tokens.get(j).type();
            if (ty == open) {
                depth++;
            } else if (ty == close) {
                depth--;
                if (depth == 0) {
                    return j + 1;
                }
            }
        }
        return tokens.size();
    }

    private static int indexOfMethod(List<NoSqlToken> tokens, NoSqlTokenType method) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type() == method) {
                return i;
            }
        }
        return -1;
    }

    private static int firstPipelineBracket(List<NoSqlToken> tokens, int methodIdx) {
        int i = skipLeadingCommas(tokens, methodIdx + 1);
        if (i < tokens.size() && tokens.get(i).type() == NoSqlTokenType.BRACKET_OPEN) {
            return i;
        }
        return -1;
    }

    private static List<Integer> collectStageBraceOpens(List<NoSqlToken> tokens, int pipelineBracketOpen) {
        List<Integer> stages = new ArrayList<>();
        int i = pipelineBracketOpen + 1;
        while (i < tokens.size() && tokens.get(i).type() != NoSqlTokenType.BRACKET_CLOSE) {
            if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            if (tokens.get(i).type() == NoSqlTokenType.BRACE_OPEN) {
                stages.add(i);
                i = skipValue(tokens, i);
            } else {
                i++;
            }
        }
        return stages;
    }

    private static String firstStageOperator(List<NoSqlToken> tokens, int stageBraceOpen) {
        List<KeyVal> pairs = parseTopLevelObjectPairs(tokens, stageBraceOpen);
        if (pairs.isEmpty()) {
            return "";
        }
        String k = pairs.get(0).key();
        return k.startsWith("$") ? k : "";
    }

    private static boolean containsStageOperators(List<NoSqlToken> tokens, List<Integer> stageOpens,
            String... ops) {
        Set<String> want = new HashSet<>(List.of(ops));
        for (int open : stageOpens) {
            List<KeyVal> pairs = parseTopLevelObjectPairs(tokens, open);
            for (KeyVal kv : pairs) {
                if (want.contains(kv.key())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean pipelineArrayContainsForbiddenStages(List<NoSqlToken> tokens, int bracketOpen) {
        List<Integer> opens = collectStageBraceOpens(tokens, bracketOpen);
        return containsStageOperators(tokens, opens, "$out", "$merge", "$indexStats");
    }

    private static int countTopLevelArrayElements(List<NoSqlToken> tokens, int bracketOpen) {
        int i = bracketOpen + 1;
        int count = 0;
        while (i < tokens.size() && tokens.get(i).type() != NoSqlTokenType.BRACKET_CLOSE) {
            if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            count++;
            i = skipValue(tokens, i);
        }
        return count;
    }

    private record JsonSchemaSpec(
            Set<String> required,
            Map<String, Set<String>> propertyTypes,
            boolean additionalPropertiesFalse
    ) {}

    private static void scanEmbeddedMongoJs(List<NoSqlToken> tokens, List<SemanticError> out, Locale loc) {
        for (int i = 0; i + 2 < tokens.size(); i++) {
            if (tokens.get(i).type() != NoSqlTokenType.OPERATOR) {
                continue;
            }
            String op = tokens.get(i).value();
            if (tokens.get(i + 1).type() != NoSqlTokenType.COLON) {
                continue;
            }
            int val = i + 2;
            if ("$where".equals(op) && val < tokens.size() && tokens.get(val).type() == NoSqlTokenType.STRING) {
                out.addAll(MongoJsParser.analyze(tokens.get(val).value(), loc));
            }
            if ("$function".equals(op) && val < tokens.size() && tokens.get(val).type() == NoSqlTokenType.BRACE_OPEN) {
                for (KeyVal kv : parseTopLevelObjectPairs(tokens, val)) {
                    if ("body".equals(kv.key()) && kv.valStart() < tokens.size()
                            && tokens.get(kv.valStart()).type() == NoSqlTokenType.STRING) {
                        out.addAll(MongoJsParser.analyze(tokens.get(kv.valStart()).value(), loc));
                        break;
                    }
                }
            }
        }
    }

    private static void applySequentialJsonSchemaAnalysis(String script, List<SemanticError> out, Locale loc) {
        boolean es = loc.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        Map<String, JsonSchemaSpec> registry = new LinkedHashMap<>();
        for (String rawChunk : splitMongoShellStatements(script)) {
            String chunk = rawChunk.strip();
            if (chunk.isEmpty() || !startsWithDbPrefix(chunk)) {
                continue;
            }
            try {
                List<NoSqlToken> tokens = new MongoDbLexer(chunk, loc).tokenize();
                Optional<JsonSchemaSpec> spec = tryParseJsonSchemaFromCreateCollection(tokens);
                if (spec.isPresent()) {
                    String coll = collectionFromDbTokens(tokens);
                    if (coll != null && !coll.isEmpty()) {
                        registry.put(coll, spec.get());
                    }
                    continue;
                }
                int ins = indexOfMethod(tokens, NoSqlTokenType.INSERT);
                if (ins >= 0) {
                    String coll = collectionFromDbTokens(tokens);
                    JsonSchemaSpec sch = coll != null ? registry.get(coll) : null;
                    if (sch != null) {
                        validateInsertTokensAgainstSchema(tokens, ins, sch, out, es);
                    }
                }
            } catch (MongoDbLexer.LexException ignored) {
                // skip chunk
            }
        }
    }

    private static void applyPriorJsonSchemaForFragment(String fullScript, String fragment, List<SemanticError> out,
            Locale loc) {
        boolean es = loc.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        List<String> stmts = StatementSplitter.split(fullScript, SqlDialect.GENERIC);
        String f = fragment == null ? "" : fragment.strip();
        int idx = -1;
        for (int i = 0; i < stmts.size(); i++) {
            if (stmts.get(i).strip().equals(f)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            String fn = normalizeWs(f);
            for (int i = 0; i < stmts.size(); i++) {
                if (normalizeWs(stmts.get(i).strip()).equals(fn)) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx <= 0) {
            return;
        }
        Map<String, JsonSchemaSpec> registry = new LinkedHashMap<>();
        for (int si = 0; si < idx; si++) {
            for (String raw : splitMongoShellStatements(stmts.get(si))) {
                String chunk = raw.strip();
                if (chunk.isEmpty() || !startsWithDbPrefix(chunk)) {
                    continue;
                }
                try {
                    List<NoSqlToken> t = new MongoDbLexer(chunk, loc).tokenize();
                    tryParseJsonSchemaFromCreateCollection(t).ifPresent(spec -> {
                        String c = collectionFromDbTokens(t);
                        if (c != null && !c.isEmpty()) {
                            registry.put(c, spec);
                        }
                    });
                } catch (MongoDbLexer.LexException ignored) {
                    // skip
                }
            }
        }
        for (String raw : splitMongoShellStatements(f)) {
            String chunk = raw.strip();
            if (chunk.isEmpty() || !startsWithDbPrefix(chunk)) {
                continue;
            }
            try {
                List<NoSqlToken> tokens = new MongoDbLexer(chunk, loc).tokenize();
                int ins = indexOfMethod(tokens, NoSqlTokenType.INSERT);
                if (ins >= 0) {
                    String coll = collectionFromDbTokens(tokens);
                    JsonSchemaSpec sch = coll != null ? registry.get(coll) : null;
                    if (sch != null) {
                        validateInsertTokensAgainstSchema(tokens, ins, sch, out, es);
                    }
                }
            } catch (MongoDbLexer.LexException ignored) {
                // skip
            }
        }
    }

    private static String normalizeWs(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String collectionFromDbTokens(List<NoSqlToken> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type() == NoSqlTokenType.COLLECTION_NAME) {
                return tokens.get(i).value();
            }
        }
        return null;
    }

    private static Optional<JsonSchemaSpec> tryParseJsonSchemaFromCreateCollection(List<NoSqlToken> tokens) {
        int m = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).type() == NoSqlTokenType.CREATE_DB_COLLECTION) {
                m = i;
                break;
            }
        }
        if (m < 0) {
            return Optional.empty();
        }
        int opt = skipLeadingCommas(tokens, m + 1);
        if (opt >= tokens.size() || tokens.get(opt).type() != NoSqlTokenType.BRACE_OPEN) {
            return Optional.empty();
        }
        List<KeyVal> top = parseTopLevelObjectPairs(tokens, opt);
        for (KeyVal kv : top) {
            if (!"validator".equals(kv.key())) {
                continue;
            }
            int vs = kv.valStart();
            if (vs >= tokens.size() || tokens.get(vs).type() != NoSqlTokenType.BRACE_OPEN) {
                continue;
            }
            for (KeyVal inner : parseTopLevelObjectPairs(tokens, vs)) {
                if (!"$jsonSchema".equals(inner.key())) {
                    continue;
                }
                int js = inner.valStart();
                if (js < tokens.size() && tokens.get(js).type() == NoSqlTokenType.BRACE_OPEN) {
                    return Optional.of(parseJsonSchemaObject(tokens, js));
                }
            }
        }
        return Optional.empty();
    }

    private static JsonSchemaSpec parseJsonSchemaObject(List<NoSqlToken> tokens, int braceOpen) {
        Set<String> required = new LinkedHashSet<>();
        Map<String, Set<String>> props = new LinkedHashMap<>();
        boolean addPropFalse = false;
        for (KeyVal kv : parseTopLevelObjectPairs(tokens, braceOpen)) {
            if ("required".equals(kv.key())) {
                required.addAll(parseStringArrayElements(tokens, kv.valStart()));
            } else if ("properties".equals(kv.key())) {
                int ps = kv.valStart();
                if (ps < tokens.size() && tokens.get(ps).type() == NoSqlTokenType.BRACE_OPEN) {
                    for (KeyVal p : parseTopLevelObjectPairs(tokens, ps)) {
                        props.put(p.key(), parseTypeFromPropertyValue(tokens, p.valStart()));
                    }
                }
            } else if ("additionalProperties".equals(kv.key())) {
                int vs = kv.valStart();
                if (vs < tokens.size() && tokens.get(vs).type() == NoSqlTokenType.BOOLEAN
                        && "false".equals(tokens.get(vs).value())) {
                    addPropFalse = true;
                }
            }
        }
        return new JsonSchemaSpec(Collections.unmodifiableSet(required), Collections.unmodifiableMap(props), addPropFalse);
    }

    private static Set<String> parseStringArrayElements(List<NoSqlToken> tokens, int bracketOrValueIdx) {
        Set<String> out = new LinkedHashSet<>();
        if (bracketOrValueIdx >= tokens.size()) {
            return out;
        }
        if (tokens.get(bracketOrValueIdx).type() != NoSqlTokenType.BRACKET_OPEN) {
            return out;
        }
        int i = bracketOrValueIdx + 1;
        while (i < tokens.size() && tokens.get(i).type() != NoSqlTokenType.BRACKET_CLOSE) {
            if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                i++;
                continue;
            }
            if (tokens.get(i).type() == NoSqlTokenType.STRING) {
                out.add(tokens.get(i).value());
            }
            i = skipValue(tokens, i);
        }
        return out;
    }

    private static Set<String> parseTypeFromPropertyValue(List<NoSqlToken> tokens, int valStart) {
        Set<String> types = new LinkedHashSet<>();
        if (valStart >= tokens.size()) {
            return types;
        }
        if (tokens.get(valStart).type() == NoSqlTokenType.STRING) {
            types.add(normalizeSchemaType(tokens.get(valStart).value()));
            return types;
        }
        if (tokens.get(valStart).type() != NoSqlTokenType.BRACE_OPEN) {
            return types;
        }
        for (KeyVal kv : parseTopLevelObjectPairs(tokens, valStart)) {
            if (!"bsonType".equals(kv.key()) && !"type".equals(kv.key())) {
                continue;
            }
            int vs = kv.valStart();
            if (vs < tokens.size() && tokens.get(vs).type() == NoSqlTokenType.STRING) {
                types.add(normalizeSchemaType(tokens.get(vs).value()));
            } else if (vs < tokens.size() && tokens.get(vs).type() == NoSqlTokenType.BRACKET_OPEN) {
                for (String x : parseStringArrayElements(tokens, vs)) {
                    types.add(normalizeSchemaType(x));
                }
            }
        }
        return types;
    }

    private static String normalizeSchemaType(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        return switch (s) {
            case "int", "integer", "long", "double", "number", "decimal", "float" -> "number";
            case "bool" -> "boolean";
            default -> s;
        };
    }

    private static void validateInsertTokensAgainstSchema(List<NoSqlToken> tokens, int insertIdx, JsonSchemaSpec sch,
            List<SemanticError> out, boolean es) {
        int docStart = skipLeadingCommas(tokens, insertIdx + 1);
        if (docStart >= tokens.size()) {
            return;
        }
        if (tokens.get(docStart).type() == NoSqlTokenType.BRACKET_OPEN) {
            int i = docStart + 1;
            while (i < tokens.size() && tokens.get(i).type() != NoSqlTokenType.BRACKET_CLOSE) {
                if (tokens.get(i).type() == NoSqlTokenType.COMMA) {
                    i++;
                    continue;
                }
                if (tokens.get(i).type() == NoSqlTokenType.BRACE_OPEN) {
                    validateOneInsertDocument(tokens, i, sch, out, es);
                }
                i = skipValue(tokens, i);
            }
            return;
        }
        if (tokens.get(docStart).type() == NoSqlTokenType.BRACE_OPEN) {
            validateOneInsertDocument(tokens, docStart, sch, out, es);
        }
    }

    private static void validateOneInsertDocument(List<NoSqlToken> tokens, int braceOpen, JsonSchemaSpec sch,
            List<SemanticError> out, boolean es) {
        List<KeyVal> fields = parseTopLevelObjectPairs(tokens, braceOpen);
        Set<String> present = new HashSet<>();
        Map<String, NoSqlTokenType> valueTypes = new HashMap<>();
        for (KeyVal kv : fields) {
            present.add(kv.key());
            int vs = kv.valStart();
            if (vs < tokens.size()) {
                valueTypes.put(kv.key(), tokens.get(vs).type());
            }
        }
        for (String req : sch.required()) {
            if (!present.contains(req)) {
                int ln = tokens.get(braceOpen).line();
                out.add(new SemanticError(
                        "MGO-SV-001",
                        t(es,
                                "Insert omits required field per $jsonSchema: " + req,
                                "Insert omite campo requerido por $jsonSchema: " + req),
                        t(es,
                                "Añada \"" + req + "\" al documento o relaje el esquema de validación.",
                                "Add \"" + req + "\" to the document or relax the validation schema."),
                        SemanticError.Severity.ERROR,
                        ln,
                        null));
            }
        }
        if (sch.additionalPropertiesFalse()) {
            for (String key : present) {
                if (sch.propertyTypes().containsKey(key) || sch.required().contains(key)) {
                    continue;
                }
                int ln = tokens.get(braceOpen).line();
                out.add(new SemanticError(
                        "MGO-SV-002",
                        t(es,
                                "Field not allowed by additionalProperties:false — " + key,
                                "Campo no permitido con additionalProperties:false — " + key),
                        t(es,
                                "Elimine el campo o declare propiedades en el JSON Schema.",
                                "Remove the field or declare it under properties in the JSON Schema."),
                        SemanticError.Severity.WARNING,
                        ln,
                        null));
            }
        }
        for (Map.Entry<String, Set<String>> e : sch.propertyTypes().entrySet()) {
            String field = e.getKey();
            if (!present.contains(field)) {
                continue;
            }
            NoSqlTokenType vt = valueTypes.get(field);
            if (vt == null) {
                continue;
            }
            String actual = inferInsertValueKind(vt);
            Set<String> expected = e.getValue();
            if (expected.isEmpty()) {
                continue;
            }
            if (!compatibleJsonSchemaTypes(expected, actual)) {
                int ln = tokens.get(braceOpen).line();
                out.add(new SemanticError(
                        "MGO-SV-003",
                        t(es,
                                "Tipo incompatible para \"" + field + "\" (esperado " + expected + ", hallado " + actual + ")",
                                "Incompatible type for \"" + field + "\" (expected " + expected + ", found " + actual + ")"),
                        t(es,
                                "Ajuste el valor al tipo declarado en $jsonSchema o actualice el esquema.",
                                "Adjust the value to the $jsonSchema-declared type or update the schema."),
                        SemanticError.Severity.ERROR,
                        ln,
                        null));
            }
        }
    }

    private static String inferInsertValueKind(NoSqlTokenType t) {
        return switch (t) {
            case STRING -> "string";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case NULL -> "null";
            case BRACE_OPEN -> "object";
            case BRACKET_OPEN -> "array";
            default -> "unknown";
        };
    }

    private static boolean compatibleJsonSchemaTypes(Set<String> expected, String actual) {
        if (expected.contains(actual)) {
            return true;
        }
        if ("number".equals(actual)) {
            return expected.stream().anyMatch(x -> "number".equals(x) || "integer".equals(x) || "int".equals(x));
        }
        if ("unknown".equals(actual)) {
            return true;
        }
        if ("null".equals(actual) && expected.contains("null")) {
            return true;
        }
        return false;
    }
}
