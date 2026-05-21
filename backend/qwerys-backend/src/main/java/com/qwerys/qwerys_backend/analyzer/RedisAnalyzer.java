package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.analyzer.nosql.LuaAnalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Semantic checks for inline Redis commands tokenized by {@link RedisLexer}.
 *
 * <p>Emits {@link SemanticError} instances with the same shape as {@link MongoDbAnalyzer}
 * ({@code code}, {@code message}, {@code suggestion}, {@code severity}).
 */
public final class RedisAnalyzer {

    private static final double LAT_MIN = -85.05112878;
    private static final double LAT_MAX = 85.05112878;
    private static final Pattern EVAL_CALL_WITHOUT_KEYS = Pattern.compile(
            "redis\\.(?:call|pcall)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern BITFIELD_NEG_OFFSET = Pattern.compile(
            "\\b(GET|SET|INCRBY)\\s+\\S+\\s+(#?-\\d+)", Pattern.CASE_INSENSITIVE);

    private static String t(boolean es, String en, String esStr) {
        return es ? esStr : en;
    }

    /**
     * Lexes {@code raw} with {@link RedisLexer} and returns findings (never {@code null}).
     */
    public List<SemanticError> analyze(String raw) {
        return analyze(raw, Locale.ENGLISH);
    }

    public List<SemanticError> analyze(String raw, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        boolean es = uiLocale != null && uiLocale.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        String q = raw == null ? "" : raw;
        if (q.isBlank()) {
            out.add(new SemanticError(
                    "RDS-SYNTAX-001",
                    es ? "La consulta Redis está vacía" : "Redis query is empty",
                    es ? "Escriba un comando Redis, por ejemplo SET mykey \"valor\" o GET mykey."
                            : "Enter a Redis command, e.g. SET mykey \"value\" or GET mykey.",
                    SemanticError.Severity.ERROR));
            return out;
        }

        String firstToken = q.stripLeading().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        boolean isEvalCommand = "EVAL".equals(firstToken) || "EVALSHA".equals(firstToken);
        if (!isEvalCommand && detectCommandInjectionAcrossNewlineSeparatedCommands(q)) {
            out.add(new SemanticError(
                    "RDS-INJ-001",
                    t(es, "Possible command injection detected", "Posible inyección de comandos detectada"),
                    t(es,
                            "Avoid concatenating user input into commands; use parameterized clients / Lua with ARGV, "
                                    + "and strip control characters (;, newlines, $(), backticks).",
                            "Evite concatenar entrada de usuario en comandos; use clientes parametrizados o Lua con "
                                    + "ARGV y elimine caracteres de control (;, saltos de línea, $(), acentos grave)."),
                    SemanticError.Severity.ERROR));
            return out;
        }
        if (isEvalCommand) {
            String tail = suffixAfterEvalFirstArgument(q);
            if (tail != null && detectEvalTailInjection(tail)) {
                out.add(new SemanticError(
                        "RDS-INJ-001",
                        t(es, "Possible command injection detected", "Posible inyección de comandos detectada"),
                        t(es,
                                "Avoid concatenating user input into commands; use parameterized clients / Lua with ARGV, "
                                        + "and strip control characters (;, newlines, $(), backticks).",
                                "Evite concatenar entrada de usuario en comandos; use clientes parametrizados o Lua con "
                                        + "ARGV y elimine caracteres de control (;, saltos de línea, $(), acentos grave)."),
                        SemanticError.Severity.ERROR));
                return out;
            }
        }

        List<String> segments = splitRedisSegments(q);
        boolean inMulti = false;
        boolean sawSubscribe = false;
        boolean sawPsubscribe = false;
        boolean watchNeedsFollowup = false;
        int multiQueued = 0;
        boolean pip002Emitted = false;
        int totalCommands = 0;

        for (String segment : segments) {
            String line = segment.strip();
            if (line.isEmpty()) {
                continue;
            }

            final List<RedisToken> tokens;
            try {
                tokens = new RedisLexer(line, uiLocale).tokenize();
            } catch (RedisLexer.LexException le) {
                String sug = es
                        ? "Revise comillas, espacios y comentarios; un comando Redis suele ser: COMANDO arg1 arg2."
                        : "Check quotes, spaces, and comments; a Redis command is usually: COMMAND arg1 arg2.";
                out.add(new SemanticError(
                        "RDS-SYNTAX-001",
                        le.getMessage(),
                        sug,
                        SemanticError.Severity.ERROR,
                        le.line(),
                        le.column()));
                continue;
            }

            String cmd = commandName(tokens);
            if (cmd == null) {
                out.add(new SemanticError(
                        "RDS-SYNTAX-001",
                        t(es, "Could not determine Redis command", "No se pudo determinar el comando Redis"),
                        t(es,
                                "Start the line with a valid command (SET, GET, HSET, ...).",
                                "Inicie la línea con un comando válido (SET, GET, HSET, ...)."),
                        SemanticError.Severity.ERROR));
                continue;
            }

            if (!RedisLexer.KNOWN_COMMANDS.contains(cmd)) {
                out.add(new SemanticError(
                        "RDS-CMD-001",
                        t(es,
                                "Redis command not supported by this analyzer: " + cmd,
                                "Comando Redis no soportado por este analizador: " + cmd),
                        t(es,
                                "Use one of the cataloged commands (strings, hashes, lists, sets, sorted sets, admin).",
                                "Use uno de los comandos catalogados (strings, hashes, listas, conjuntos, sorted sets, admin)."),
                        SemanticError.Severity.ERROR));
                continue;
            }

            List<RedisToken> args = argsOnly(tokens);
            validateArity(cmd, args, out, es);

            applyCommandRules(cmd, args, tokens, out, es);
            applyAclRules(cmd, args, out, es);
            applyModuleRules(cmd, args, out, es);

            totalCommands++;
            if (!pip002Emitted && totalCommands > 1000) {
                pip002Emitted = true;
                out.add(new SemanticError(
                        "RDS-PIP-002",
                        t(es,
                                "Large pipeline (>1000 commands): consider splitting into batches",
                                "Pipeline grande (>1000 comandos): considere dividir en lotes"),
                        t(es,
                                "Send smaller batches or use Redis Cluster-aware clients to avoid timeouts and memory spikes.",
                                "Envíe lotes más pequeños o use clientes compatibles con clúster para evitar timeouts y picos de memoria."),
                        SemanticError.Severity.WARNING));
            }

            if ("UNWATCH".equals(cmd)) {
                watchNeedsFollowup = false;
            }
            if ("WATCH".equals(cmd)) {
                watchNeedsFollowup = true;
            }
            if ("EXEC".equals(cmd)) {
                watchNeedsFollowup = false;
            }

            if (inMulti && !"MULTI".equals(cmd) && !"EXEC".equals(cmd) && !"DISCARD".equals(cmd)) {
                multiQueued++;
                if (!pip002Emitted && multiQueued > 1000) {
                    pip002Emitted = true;
                    out.add(new SemanticError(
                            "RDS-PIP-002",
                            t(es,
                                    "Transaction queue exceeds 1000 commands: consider smaller batches",
                                    "La cola de transacción supera 1000 comandos: use lotes más pequeños"),
                            t(es,
                                    "Split work across multiple MULTI/EXEC blocks or redesign hot paths.",
                                    "Divida el trabajo en varios bloques MULTI/EXEC o rediseñe los caminos críticos."),
                            SemanticError.Severity.WARNING));
                }
                if (dangerousPipelineCommand(cmd, args)) {
                    out.add(new SemanticError(
                            "RDS-PIP-001",
                            t(es,
                                    "Dangerous command (KEYS or FLUSH*) inside MULTI/EXEC pipeline",
                                    "Comando peligroso (KEYS o FLUSH*) dentro de un pipeline MULTI/EXEC"),
                            t(es,
                                    "Avoid KEYS and FLUSHDB/FLUSHALL inside transactions; use SCAN and controlled maintenance.",
                                    "Evite KEYS y FLUSHDB/FLUSHALL dentro de transacciones; use SCAN y mantenimiento controlado."),
                            SemanticError.Severity.ERROR));
                }
            }

            if ("HMSET".equals(cmd)) {
                out.add(new SemanticError(
                        "RDS-HMSET-001",
                        t(es, "HMSET is deprecated. Use HSET instead", "HMSET está obsoleto. Use HSET"),
                        t(es,
                                "Replace HMSET with HSET; since Redis 4.0, HSET accepts multiple field/value pairs.",
                                "Sustituya HMSET por HSET; desde Redis 4.0, HSET acepta varios pares campo/valor."),
                        SemanticError.Severity.INFO));
            }

            if ("KEYS".equals(cmd)) {
                String pat = firstPatternOrKeyArg(args);
                if (pat != null && "*".equals(pat)) {
                    out.add(new SemanticError(
                            "RDS-KEYS-001",
                            t(es, "KEYS * blocks the server. Use SCAN instead", "KEYS * bloquea el servidor. Use SCAN"),
                            t(es,
                                    "Prefer SCAN MATCH with a cursor (or maintain an index set) to iterate keys without blocking.",
                                    "Prefiera SCAN MATCH con cursor (o mantenga un índice) para recorrer claves sin bloquear."),
                            SemanticError.Severity.ERROR));
                } else if (pat != null) {
                    out.add(new SemanticError(
                            "RDS-KEYS-002",
                            t(es,
                                    "KEYS scans the entire keyspace even with a pattern — prefer SCAN in production",
                                    "KEYS escanea todo el keyspace aunque uses un patrón — prefiera SCAN en producción"),
                            t(es,
                                    "Replace KEYS pattern with SCAN cursor MATCH pattern COUNT n to avoid blocking Redis.",
                                    "Sustituya KEYS patrón por SCAN con cursor MATCH patrón COUNT n para no bloquear Redis."),
                            SemanticError.Severity.WARNING));
                }
            }

            if ("FLUSHDB".equals(cmd) || "FLUSHALL".equals(cmd)) {
                if (!hasConfirmAck(args)) {
                    String msg = "FLUSHDB".equals(cmd)
                            ? t(es,
                                    "FLUSHDB will delete ALL keys in the current database",
                                    "FLUSHDB eliminará TODAS las claves de la base de datos actual")
                            : t(es,
                                    "FLUSHALL will delete ALL keys in every database",
                                    "FLUSHALL eliminará TODAS las claves de todas las bases de datos");
                    out.add(new SemanticError(
                            "RDS-FLUSH-001",
                            msg,
                            t(es,
                                    "Add CONFIRM as the last token only in controlled environments, or remove the command. "
                                            + "This analyzer requires CONFIRM to acknowledge destructive flushes.",
                                    "Añada CONFIRM solo como último token en entornos controlados, o elimine el comando. "
                                            + "Este analizador exige CONFIRM para reconocer los FLUSH destructivos."),
                            SemanticError.Severity.ERROR));
                }
            }

            if ("SET".equals(cmd)) {
                if (isSessionLikeKey(tokens) && !setHasExpiryOption(tokens)) {
                    out.add(new SemanticError(
                            "RDS-SESS-001",
                            t(es,
                                    "Session-like key without expiry — possible memory leak",
                                    "Clave tipo sesión sin caducidad — posible fuga de memoria"),
                            t(es,
                                    "Add EX/PX/EXAT/PXAT to SET or call EXPIRE so session/token keys are evicted.",
                                    "Añada EX/PX/EXAT/PXAT a SET o llame a EXPIRE para que las claves de sesión/token expiren."),
                            SemanticError.Severity.WARNING));
                }
            }

            if ("WATCH".equals(cmd) && inMulti) {
                out.add(new SemanticError(
                        "RDS-TX-001",
                        t(es,
                                "WATCH must be called before MULTI, not inside a transaction",
                                "WATCH debe llamarse antes de MULTI, no dentro de una transacción"),
                        t(es,
                                "Call WATCH on keys before MULTI, or UNWATCH before starting MULTI.",
                                "Llame a WATCH sobre las claves antes de MULTI, o UNWATCH antes de iniciar MULTI."),
                        SemanticError.Severity.ERROR));
            }
            if ("MULTI".equals(cmd)) {
                inMulti = true;
                multiQueued = 0;
            }
            if ("EXEC".equals(cmd)) {
                if (!inMulti) {
                    out.add(new SemanticError(
                            "RDS-TX-002",
                            t(es,
                                    "EXEC without MULTI: use MULTI before EXEC to start a transaction",
                                    "EXEC sin MULTI: use MULTI antes de EXEC para iniciar una transacción"),
                            t(es,
                                    "Send MULTI first, queue commands, then EXEC.",
                                    "Envíe MULTI primero, encole comandos y luego EXEC."),
                            SemanticError.Severity.ERROR));
                }
                inMulti = false;
                multiQueued = 0;
            }
            if ("DISCARD".equals(cmd)) {
                if (!inMulti) {
                    out.add(new SemanticError(
                            "RDS-TX-003",
                            t(es, "DISCARD without active MULTI block", "DISCARD sin bloque MULTI activo"),
                            t(es,
                                    "DISCARD only applies after MULTI when no EXEC has run.",
                                    "DISCARD solo aplica tras MULTI cuando no se ha ejecutado EXEC."),
                            SemanticError.Severity.ERROR));
                }
                inMulti = false;
                multiQueued = 0;
            }

            if (inMulti && isBlockingListCommand(cmd)) {
                out.add(new SemanticError(
                        "RDS-TX-004",
                        t(es,
                                "Blocking commands inside MULTI/EXEC may cause issues; prefer non-blocking variants",
                                "Comandos bloqueantes dentro de MULTI/EXEC pueden causar problemas; prefiera variantes no bloqueantes"),
                        t(es,
                                "Avoid BLPOP/BRPOP/BRPOPLPUSH inside MULTI/EXEC; use LPOP/RPOP or design outside the transaction.",
                                "Evite BLPOP/BRPOP/BRPOPLPUSH dentro de MULTI/EXEC; use LPOP/RPOP o diseñe fuera de la transacción."),
                        SemanticError.Severity.WARNING));
            }

            if ("SUBSCRIBE".equals(cmd)) {
                sawSubscribe = true;
            }
            if ("PSUBSCRIBE".equals(cmd)) {
                sawPsubscribe = true;
            }
        }

        if (sawSubscribe && sawPsubscribe) {
            out.add(new SemanticError(
                    "RDS-PUB-001",
                    t(es,
                            "Mixing SUBSCRIBE and PSUBSCRIBE on the same connection is allowed but can be confusing",
                            "Mezclar SUBSCRIBE y PSUBSCRIBE en la misma conexión está permitido pero puede confundir"),
                    t(es,
                            "Use one style or document which channels are pattern-matched.",
                            "Use un solo estilo o documente qué canales usan patrones."),
                    SemanticError.Severity.INFO));
        }

        if (watchNeedsFollowup) {
            out.add(new SemanticError(
                    "RDS-PIP-003",
                    t(es,
                            "WATCH used but no EXEC appears later in this script",
                            "Se usó WATCH pero no hay EXEC después en este script"),
                    t(es,
                            "Follow WATCH with MULTI, queued commands, and EXEC, or call UNWATCH if the transaction is abandoned.",
                            "Tras WATCH use MULTI, comandos en cola y EXEC, o llame a UNWATCH si abandona la transacción."),
                    SemanticError.Severity.WARNING));
        }

        return out;
    }

    /**
     * Per-fragment analysis for multi-statement Redis scripts split on {@code ;}: transaction and
     * pub/sub mix rules are evaluated on the whole script via {@link #analyzeCrossScriptOnly}.
     */
    public List<SemanticError> analyzeForMultiStatementFragment(String stmt, Locale uiLocale) {
        List<SemanticError> out = analyze(stmt, uiLocale);
        out.removeIf(e -> {
            String c = e.code();
            return "RDS-TX-001".equals(c)
                    || "RDS-TX-002".equals(c)
                    || "RDS-TX-003".equals(c)
                    || "RDS-TX-004".equals(c)
                    || "RDS-PUB-001".equals(c)
                    || "RDS-PIP-001".equals(c)
                    || "RDS-PIP-002".equals(c)
                    || "RDS-PIP-003".equals(c);
        });
        return out;
    }

    /**
     * Transaction and pub/sub findings that need the full script (e.g. {@code MULTI; ...; EXEC} split
     * across statement separators).
     */
    public List<SemanticError> analyzeCrossScriptOnly(String raw, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        boolean es = uiLocale != null && uiLocale.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
        String q = raw == null ? "" : raw;
        if (q.isBlank()) {
            return out;
        }

        List<String> segments = splitRedisSegments(q.replace(';', '\n'));
        boolean inMulti = false;
        boolean sawSubscribe = false;
        boolean sawPsubscribe = false;
        boolean watchNeedsFollowup = false;
        int multiQueued = 0;
        boolean pip002Emitted = false;
        int totalCommands = 0;

        for (String segment : segments) {
            String line = segment.strip();
            if (line.isEmpty()) {
                continue;
            }

            final List<RedisToken> tokens;
            try {
                tokens = new RedisLexer(line, uiLocale).tokenize();
            } catch (RedisLexer.LexException ignored) {
                continue;
            }

            String cmd = commandName(tokens);
            if (cmd == null || !RedisLexer.KNOWN_COMMANDS.contains(cmd)) {
                continue;
            }

            List<RedisToken> args = argsOnly(tokens);

            totalCommands++;
            if (!pip002Emitted && totalCommands > 1000) {
                pip002Emitted = true;
                out.add(new SemanticError(
                        "RDS-PIP-002",
                        t(es,
                                "Large pipeline (>1000 commands): consider splitting into batches",
                                "Pipeline grande (>1000 comandos): considere dividir en lotes"),
                        t(es,
                                "Send smaller batches or use Redis Cluster-aware clients to avoid timeouts and memory spikes.",
                                "Envíe lotes más pequeños o use clientes compatibles con clúster para evitar timeouts y picos de memoria."),
                        SemanticError.Severity.WARNING));
            }

            if ("UNWATCH".equals(cmd)) {
                watchNeedsFollowup = false;
            }
            if ("WATCH".equals(cmd)) {
                watchNeedsFollowup = true;
            }
            if ("EXEC".equals(cmd)) {
                watchNeedsFollowup = false;
            }

            if (inMulti && !"MULTI".equals(cmd) && !"EXEC".equals(cmd) && !"DISCARD".equals(cmd)) {
                multiQueued++;
                if (!pip002Emitted && multiQueued > 1000) {
                    pip002Emitted = true;
                    out.add(new SemanticError(
                            "RDS-PIP-002",
                            t(es,
                                    "Transaction queue exceeds 1000 commands: consider smaller batches",
                                    "La cola de transacción supera 1000 comandos: use lotes más pequeños"),
                            t(es,
                                    "Split work across multiple MULTI/EXEC blocks or redesign hot paths.",
                                    "Divida el trabajo en varios bloques MULTI/EXEC o rediseñe los caminos críticos."),
                            SemanticError.Severity.WARNING));
                }
                if (dangerousPipelineCommand(cmd, args)) {
                    out.add(new SemanticError(
                            "RDS-PIP-001",
                            t(es,
                                    "Dangerous command (KEYS or FLUSH*) inside MULTI/EXEC pipeline",
                                    "Comando peligroso (KEYS o FLUSH*) dentro de un pipeline MULTI/EXEC"),
                            t(es,
                                    "Avoid KEYS and FLUSHDB/FLUSHALL inside transactions; use SCAN and controlled maintenance.",
                                    "Evite KEYS y FLUSHDB/FLUSHALL dentro de transacciones; use SCAN y mantenimiento controlado."),
                            SemanticError.Severity.ERROR));
                }
            }

            if ("WATCH".equals(cmd) && inMulti) {
                out.add(new SemanticError(
                        "RDS-TX-001",
                        t(es,
                                "WATCH must be called before MULTI, not inside a transaction",
                                "WATCH debe llamarse antes de MULTI, no dentro de una transacción"),
                        t(es,
                                "Call WATCH on keys before MULTI, or UNWATCH before starting MULTI.",
                                "Llame a WATCH sobre las claves antes de MULTI, o UNWATCH antes de iniciar MULTI."),
                        SemanticError.Severity.ERROR));
            }
            if ("MULTI".equals(cmd)) {
                inMulti = true;
                multiQueued = 0;
            }
            if ("EXEC".equals(cmd)) {
                if (!inMulti) {
                    out.add(new SemanticError(
                            "RDS-TX-002",
                            t(es,
                                    "EXEC without MULTI: use MULTI before EXEC to start a transaction",
                                    "EXEC sin MULTI: use MULTI antes de EXEC para iniciar una transacción"),
                            t(es,
                                    "Send MULTI first, queue commands, then EXEC.",
                                    "Envíe MULTI primero, encole comandos y luego EXEC."),
                            SemanticError.Severity.ERROR));
                }
                inMulti = false;
                multiQueued = 0;
            }
            if ("DISCARD".equals(cmd)) {
                if (!inMulti) {
                    out.add(new SemanticError(
                            "RDS-TX-003",
                            t(es, "DISCARD without active MULTI block", "DISCARD sin bloque MULTI activo"),
                            t(es,
                                    "DISCARD only applies after MULTI when no EXEC has run.",
                                    "DISCARD solo aplica tras MULTI cuando no se ha ejecutado EXEC."),
                            SemanticError.Severity.ERROR));
                }
                inMulti = false;
                multiQueued = 0;
            }

            if (inMulti && isBlockingListCommand(cmd)) {
                out.add(new SemanticError(
                        "RDS-TX-004",
                        t(es,
                                "Blocking commands inside MULTI/EXEC may cause issues; prefer non-blocking variants",
                                "Comandos bloqueantes dentro de MULTI/EXEC pueden causar problemas; prefiera variantes no bloqueantes"),
                        t(es,
                                "Avoid BLPOP/BRPOP/BRPOPLPUSH inside MULTI/EXEC; use LPOP/RPOP or design outside the transaction.",
                                "Evite BLPOP/BRPOP/BRPOPLPUSH dentro de MULTI/EXEC; use LPOP/RPOP o diseñe fuera de la transacción."),
                        SemanticError.Severity.WARNING));
            }

            if ("SUBSCRIBE".equals(cmd)) {
                sawSubscribe = true;
            }
            if ("PSUBSCRIBE".equals(cmd)) {
                sawPsubscribe = true;
            }
        }

        if (inMulti) {
            out.add(new SemanticError(
                    "RDS-TX-005",
                    t(es,
                            "Script ends inside MULTI — send EXEC or DISCARD to close the transaction",
                            "El script termina dentro de MULTI: envíe EXEC o DISCARD para cerrar la transacción"),
                    t(es,
                            "Every MULTI must be followed by EXEC or DISCARD before the end of the script.",
                            "Cada MULTI debe ir seguido de EXEC o DISCARD antes de terminar el script."),
                    SemanticError.Severity.WARNING));
        }

        if (sawSubscribe && sawPsubscribe) {
            out.add(new SemanticError(
                    "RDS-PUB-001",
                    t(es,
                            "Mixing SUBSCRIBE and PSUBSCRIBE on the same connection is allowed but can be confusing",
                            "Mezclar SUBSCRIBE y PSUBSCRIBE en la misma conexión está permitido pero puede confundir"),
                    t(es,
                            "Use one style or document which channels are pattern-matched.",
                            "Use un solo estilo o documente qué canales usan patrones."),
                    SemanticError.Severity.INFO));
        }

        if (watchNeedsFollowup) {
            out.add(new SemanticError(
                    "RDS-PIP-003",
                    t(es,
                            "WATCH used but no EXEC appears later in this script",
                            "Se usó WATCH pero no hay EXEC después en este script"),
                    t(es,
                            "Follow WATCH with MULTI, queued commands, and EXEC, or call UNWATCH if the transaction is abandoned.",
                            "Tras WATCH use MULTI, comandos en cola y EXEC, o llame a UNWATCH si abandona la transacción."),
                    SemanticError.Severity.WARNING));
        }

        return out;
    }

    private static List<String> splitRedisSegments(String raw) {
        String[] parts = raw.split("\\R+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            out.add(raw.strip());
        }
        return out;
    }

    private static boolean isBlockingListCommand(String cmd) {
        return "BLPOP".equals(cmd) || "BRPOP".equals(cmd) || "BRPOPLPUSH".equals(cmd);
    }

    /** KEYS or FLUSHDB / FLUSHALL inside a MULTI/EXEC queue (Day 24E — RDS-PIP-001). */
    private static boolean dangerousPipelineCommand(String cmd, List<RedisToken> args) {
        if ("FLUSHDB".equals(cmd) || "FLUSHALL".equals(cmd)) {
            return true;
        }
        return "KEYS".equals(cmd);
    }

    private static boolean isModuleCommand(String cmd) {
        if (cmd == null || !cmd.contains(".")) {
            return false;
        }
        return cmd.startsWith("JSON.") || cmd.startsWith("FT.") || cmd.startsWith("GRAPH.") || cmd.startsWith("TS.");
    }

    private static boolean moduleArityBad(String cmd, int n) {
        return switch (cmd) {
            case "JSON.SET", "JSON.MSET", "JSON.ARRAPPEND", "JSON.NUMINCRBY" -> n < 3;
            case "JSON.GET", "JSON.TYPE", "JSON.STRLEN", "JSON.DEL", "JSON.OBJKEYS" -> n < 1;
            case "JSON.MGET" -> n < 2;
            case "JSON.ARRLEN", "JSON.TOGGLE", "JSON.STRAPPEND" -> n < 2;
            case "FT.SEARCH", "FT.AGGREGATE", "FT.EXPLAIN" -> n < 2;
            case "FT.CREATE", "FT.DROPINDEX", "FT.INFO", "FT.ALTER" -> n < 1;
            case "GRAPH.QUERY", "GRAPH.DELETE", "GRAPH.EXPLAIN" -> n < 2;
            case "GRAPH.LIST" -> false;
            case "TS.ADD", "TS.INCRBY" -> n < 3;
            case "TS.MADD" -> n < 4;
            case "TS.RANGE", "TS.MRANGE" -> n < 3;
            case "TS.CREATE", "TS.GET", "TS.INFO", "TS.MGET" -> n < 1;
            default -> false;
        };
    }

    private static void applyAclRules(String cmd, List<RedisToken> args, List<SemanticError> out, boolean es) {
        if (!"ACL".equals(cmd)) {
            return;
        }
        List<String> av = argValues(args);
        if (av.isEmpty()) {
            return;
        }
        String sub = av.get(0).toUpperCase(Locale.ROOT);
        if ("SETUSER".equals(sub)) {
            String joined = String.join(" ", av).toUpperCase(Locale.ROOT);
            if (joined.contains("+@ALL")) {
                out.add(new SemanticError(
                        "RDS-ACL-001",
                        t(es,
                                "ACL SETUSER grants all command categories (+@all) — very broad permissions",
                                "ACL SETUSER otorga todas las categorías de comandos (+@all) — permisos muy amplios"),
                        t(es,
                                "Prefer narrow ACL categories (+@read, +@write, +@string, …) instead of +@all.",
                                "Prefiera categorías ACL acotadas (+@read, +@write, +@string, …) en lugar de +@all."),
                        SemanticError.Severity.WARNING));
            }
            boolean nopass = av.stream().anyMatch(s -> "nopass".equalsIgnoreCase(s.trim()));
            boolean hasPasswordHash = false;
            for (String a : av) {
                String s = a != null ? a.trim() : "";
                if (s.startsWith(">") || s.startsWith("#")) {
                    hasPasswordHash = true;
                    break;
                }
            }
            if (!nopass && !hasPasswordHash) {
                out.add(new SemanticError(
                        "RDS-ACL-002",
                        t(es,
                                "ACL SETUSER without a password hash (>...) or NOPASS — unsafe for production",
                                "ACL SETUSER sin hash de contraseña (>...) ni NOPASS — inseguro en producción"),
                        t(es,
                                "Add a password with >hashedpassword or explicit NOPASS only for non-password users.",
                                "Añada contraseña con >hash o NOPASS explícito solo para usuarios sin contraseña."),
                        SemanticError.Severity.ERROR));
            }
        }
        if ("DELUSER".equals(sub) && av.size() >= 2 && "default".equalsIgnoreCase(av.get(1).trim())) {
            out.add(new SemanticError(
                    "RDS-ACL-003",
                    t(es, "Cannot delete the default Redis ACL user", "No puede borrar el usuario ACL default de Redis"),
                    t(es,
                            "The default user is required; revoke permissions instead of deleting it.",
                            "El usuario default es obligatorio; revoque permisos en lugar de borrarlo."),
                    SemanticError.Severity.ERROR));
        }
    }

    private static void applyModuleRules(String cmd, List<RedisToken> args, List<SemanticError> out, boolean es) {
        if (!isModuleCommand(cmd)) {
            return;
        }
        List<String> av = argValues(args);
        out.add(new SemanticError(
                "RDS-MOD-001",
                t(es,
                        "Module command: verify the module is loaded on the target server (MODULE LIST)",
                        "Comando de módulo: verifique que el módulo esté cargado en el servidor (MODULE LIST)"),
                t(es,
                        "Check RedisJSON / RediSearch / RedisGraph / RedisTimeSeries availability before relying on this command.",
                        "Compruebe RedisJSON / RediSearch / RedisGraph / RedisTimeSeries antes de depender de este comando."),
                SemanticError.Severity.INFO));
        if ("FT.SEARCH".equals(cmd) && indexOfIgnoreCase(av, "LIMIT") < 0) {
            out.add(new SemanticError(
                    "RDS-MOD-002",
                    t(es,
                            "FT.SEARCH without LIMIT may return very large result sets",
                            "FT.SEARCH sin LIMIT puede devolver conjuntos de resultados muy grandes"),
                    t(es,
                            "Add LIMIT offset count (and consider SORTBY) to cap work and payload size.",
                            "Añada LIMIT offset count (y considere SORTBY) para acotar trabajo y tamaño de respuesta."),
                    SemanticError.Severity.WARNING));
        }
    }

    private static void applyCommandRules(
            String cmd, List<RedisToken> args, List<RedisToken> tokens, List<SemanticError> out, boolean es) {
        List<String> av = argValues(args);

        if ("XADD".equals(cmd)) {
            analyzeXadd(av, out, es);
        } else if ("XREADGROUP".equals(cmd)) {
            int g = indexOfIgnoreCase(av, "GROUP");
            if (g < 0 || av.size() < g + 3) {
                out.add(new SemanticError(
                        "RDS-XStream-001",
                        t(es,
                                "XREADGROUP requires GROUP <groupname> <consumername>",
                                "XREADGROUP requiere GROUP <grupo> <consumidor>"),
                        t(es,
                                "Add GROUP mygroup myconsumer after options and stream keys.",
                                "Añada GROUP mygroup myconsumer tras las opciones y las claves de stream."),
                        SemanticError.Severity.ERROR));
            }
        } else if ("XGROUP".equals(cmd)) {
            if (indexOfIgnoreCase(av, "CREATE") >= 0 && indexOfIgnoreCase(av, "MKSTREAM") < 0) {
                out.add(new SemanticError(
                        "RDS-XStream-002",
                        t(es,
                                "Stream may not exist; use XGROUP CREATE key group $ MKSTREAM if missing",
                                "El stream puede no existir; use XGROUP CREATE clave grupo $ MKSTREAM si falta"),
                        t(es,
                                "Add MKSTREAM when creating a consumer group on a stream that might not exist yet.",
                                "Añada MKSTREAM al crear un grupo de consumo en un stream que aún podría no existir."),
                        SemanticError.Severity.WARNING));
            }
        } else if ("XPENDING".equals(cmd)) {
            if (av.size() <= 2) {
                out.add(new SemanticError(
                        "RDS-XStream-003",
                        t(es,
                                "XPENDING without a range returns summary only; add start/end/count for details",
                                "XPENDING sin rango solo devuelve resumen; añada inicio/fin/conteo para detalles"),
                        t(es,
                                "Pass start/end/count after key and group to inspect pending entries.",
                                "Pase inicio/fin/conteo tras la clave y el grupo para ver entradas pendientes."),
                        SemanticError.Severity.INFO));
            }
        } else if ("PUBLISH".equals(cmd)) {
            if (!av.isEmpty() && av.get(0).isEmpty()) {
                out.add(new SemanticError(
                        "RDS-PUB-002",
                        t(es, "Publishing to an empty channel name; check the channel", "Publicación a canal vacío; revise el nombre"),
                        t(es, "Use a non-empty channel string.", "Use una cadena de canal no vacía."),
                        SemanticError.Severity.WARNING));
            }
        } else if ("PUBSUB".equals(cmd) && av.size() >= 2
                && "CHANNELS".equalsIgnoreCase(av.get(0))
                && "*".equals(av.get(1))) {
            out.add(new SemanticError(
                    "RDS-PUB-003",
                    t(es,
                            "PUBSUB CHANNELS * lists all channels; may be slow on busy Redis",
                            "PUBSUB CHANNELS * lista todos los canales; puede ser lento en Redis muy cargado"),
                    t(es,
                            "Prefer a narrower pattern or monitor channel count in staging first.",
                            "Prefiera un patrón más estrecho o supervise el conteo de canales en staging."),
                    SemanticError.Severity.WARNING));
        } else if ("GEOADD".equals(cmd)) {
            analyzeGeoadd(av, out, es);
        } else if ("GEORADIUS".equals(cmd)) {
            out.add(new SemanticError(
                    "RDS-GEO-001",
                    t(es, "GEORADIUS is deprecated since Redis 6.2; use GEOSEARCH", "GEORADIUS está obsoleto desde Redis 6.2; use GEOSEARCH"),
                    t(es,
                            "Rewrite using GEOSEARCH/GEOSEARCHSTORE with FROMMEMBER/FROMLONLAT.",
                            "Reescriba con GEOSEARCH/GEOSEARCHSTORE y FROMMEMBER/FROMLONLAT."),
                    SemanticError.Severity.WARNING));
        } else if ("GEODIST".equals(cmd)) {
            analyzeGeodist(av, out, es);
        } else if ("PFCOUNT".equals(cmd)) {
            if (args.stream().filter(rt -> rt.type() == RedisTokenType.KEY).count() > 10) {
                out.add(new SemanticError(
                        "RDS-HLL-001",
                        t(es,
                                "PFCOUNT with many keys triggers cross-slot merging; consider PFMERGE first",
                                "PFCOUNT con muchas claves implica fusión entre slots; considere PFMERGE antes"),
                        t(es,
                                "PFMERGE into one key, then PFCOUNT once for cluster-friendly workloads.",
                                "PFMERGE en una sola clave y luego PFCOUNT una vez para cargas en clúster."),
                        SemanticError.Severity.WARNING));
            }
        } else if ("PFMERGE".equals(cmd)) {
            if (av.size() >= 2) {
                String dest = av.get(0);
                for (int i = 1; i < av.size(); i++) {
                    if (dest.equals(av.get(i))) {
                        out.add(new SemanticError(
                                "RDS-HLL-002",
                                t(es,
                                        "PFMERGE destination is also a source; valid but easy to misread",
                                        "El destino de PFMERGE también es origen; es válido pero puede confundir"),
                                t(es,
                                        "Double-check that merging a key with itself is intentional.",
                                        "Confirme que fusionar una clave consigo misma es intencionado."),
                                SemanticError.Severity.WARNING));
                        break;
                    }
                }
            }
        } else if ("EVAL".equals(cmd) || "EVALSHA".equals(cmd)) {
            analyzeEval(cmd, av, out, es);
            mergeLuaFindings(cmd, av, out, es);
        } else if ("SCRIPT".equals(cmd)) {
            if (av.stream().anyMatch(s -> "FLUSH".equalsIgnoreCase(s))) {
                out.add(new SemanticError(
                        "RDS-Script-001",
                        t(es,
                                "SCRIPT FLUSH removes all cached scripts; avoid in production without care",
                                "SCRIPT FLUSH borra todos los scripts en caché; evítelo en producción sin precaución"),
                        t(es, "Use only in dev/staging or during controlled maintenance.",
                                "Úselo solo en dev/staging o en mantenimiento controlado."),
                        SemanticError.Severity.WARNING));
            }
        } else if ("BITFIELD".equals(cmd)) {
            String joined = String.join(" ", av);
            if (BITFIELD_NEG_OFFSET.matcher(joined).find()) {
                out.add(new SemanticError(
                        "RDS-BIT-001",
                        t(es, "Bitfield offset must be non-negative", "El offset de BITFIELD debe ser no negativo"),
                        t(es,
                                "Use non-negative offsets for GET/SET/INCRBY in BITFIELD.",
                                "Use offsets no negativos en GET/SET/INCRBY de BITFIELD."),
                        SemanticError.Severity.ERROR));
            }
        } else if ("BITCOUNT".equals(cmd)) {
            out.add(new SemanticError(
                    "RDS-BIT-002",
                    t(es,
                            "BITCOUNT on a missing key returns 0 (not an error)",
                            "BITCOUNT sobre clave inexistente devuelve 0 (no es error)"),
                    t(es,
                            "No change needed — Redis treats a missing key as an empty string bitmap.",
                            "No hace falta cambiar nada: Redis trata la clave ausente como bitmap vacío."),
                    SemanticError.Severity.INFO));
        } else if ("BITOP".equals(cmd)) {
            if (!av.isEmpty() && "NOT".equalsIgnoreCase(av.get(0)) && av.size() != 3) {
                out.add(new SemanticError(
                        "RDS-BIT-003",
                        t(es, "BITOP NOT takes exactly one source key", "BITOP NOT requiere exactamente una clave origen"),
                        t(es, "Use: BITOP NOT destkey sourcekey", "Use: BITOP NOT destino origen"),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private static void analyzeXadd(List<String> av, List<SemanticError> out, boolean es) {
        if (av.isEmpty()) {
            out.add(new SemanticError(
                    "RDS-XStream-010",
                    t(es,
                            "XADD requires stream key, ID (* for auto), and at least one field-value pair",
                            "XADD requiere clave de stream, ID (* automático) y al menos un par campo-valor"),
                    t(es,
                            "Provide: XADD key * field value [field value ...] (optional MAXLEN/~).",
                            "Formato: XADD clave * campo valor [campo valor ...] (opcional MAXLEN/~)."),
                    SemanticError.Severity.ERROR));
            return;
        }
        int i = 1;
        if (i < av.size() && "NOMKSTREAM".equalsIgnoreCase(av.get(i))) {
            i++;
        }
        if (i < av.size() && "MAXLEN".equalsIgnoreCase(av.get(i))) {
            i++;
            if (i < av.size()) {
                String next = av.get(i);
                if ("~".equals(next)) {
                    i++;
                    if (i < av.size()) {
                        i++;
                    }
                } else if ("=".equals(next)) {
                    out.add(new SemanticError(
                            "RDS-XStream-004",
                            t(es,
                                    "XADD MAXLEN without ~ uses exact trimming (slow); prefer MAXLEN ~ for approximate",
                                    "XADD MAXLEN sin ~ usa recorte exacto (lento); prefiera MAXLEN ~ para aproximado"),
                            t(es, "Prefer MAXLEN ~ N unless you need strict length caps.",
                                    "Prefiera MAXLEN ~ N salvo que necesite un tope estricto."),
                            SemanticError.Severity.WARNING));
                    i++;
                    if (i < av.size()) {
                        i++;
                    }
                } else {
                    out.add(new SemanticError(
                            "RDS-XStream-004",
                            t(es,
                                    "XADD MAXLEN without ~ uses exact trimming (slow); prefer MAXLEN ~ for approximate",
                                    "XADD MAXLEN sin ~ usa recorte exacto (lento); prefiera MAXLEN ~ para aproximado"),
                            t(es, "Prefer MAXLEN ~ N unless you need strict length caps.",
                                    "Prefiera MAXLEN ~ N salvo que necesite un tope estricto."),
                            SemanticError.Severity.WARNING));
                    i++;
                }
            }
        }
        if (av.size() - i < 3) {
            out.add(new SemanticError(
                    "RDS-XStream-010",
                    t(es,
                            "XADD requires stream key, ID (* for auto), and at least one field-value pair",
                            "XADD requiere clave de stream, ID (* automático) y al menos un par campo-valor"),
                    t(es,
                            "Provide: XADD key * field value [field value ...] (optional MAXLEN/~).",
                            "Formato: XADD clave * campo valor [campo valor ...] (opcional MAXLEN/~)."),
                    SemanticError.Severity.ERROR));
        }
    }

    private static void analyzeGeoadd(List<String> av, List<SemanticError> out, boolean es) {
        if (av.size() < 4) {
            return;
        }
        for (int k = 1; k + 2 < av.size(); k += 3) {
            Double lon = parseDoubleLoose(av.get(k));
            Double lat = parseDoubleLoose(av.get(k + 1));
            if (lat != null && (lat < LAT_MIN || lat > LAT_MAX)) {
                out.add(new SemanticError(
                        "RDS-GEO-002",
                        t(es,
                                "Latitude must be between -85.05112878 and 85.05112878",
                                "La latitud debe estar entre -85.05112878 y 85.05112878"),
                        t(es,
                                "Fix GEOADD longitude latitude member order and valid ranges.",
                                "Revise el orden longitud latitud miembro y los rangos válidos en GEOADD."),
                        SemanticError.Severity.ERROR));
            }
            if (lon != null && (lon < -180.0 || lon > 180.0)) {
                out.add(new SemanticError(
                        "RDS-GEO-003",
                        t(es, "Longitude must be between -180 and 180", "La longitud debe estar entre -180 y 180"),
                        t(es,
                                "GEOADD expects longitude before latitude for each point.",
                                "GEOADD espera longitud antes que latitud para cada punto."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private static void analyzeGeodist(List<String> av, List<SemanticError> out, boolean es) {
        if (av.size() < 4) {
            return;
        }
        String unit = av.get(av.size() - 1);
        String u = unit.toLowerCase(Locale.ROOT);
        if (!"m".equals(u) && !"km".equals(u) && !"mi".equals(u) && !"ft".equals(u)) {
            out.add(new SemanticError(
                    "RDS-GEO-004",
                    t(es, "GEODIST unit must be: m, km, mi, or ft", "La unidad de GEODIST debe ser: m, km, mi o ft"),
                    t(es, "Append a valid unit as the last argument.",
                            "Añada una unidad válida como último argumento."),
                    SemanticError.Severity.ERROR));
        }
    }

    /** Runs {@link LuaAnalyzer} on the script body for {@code EVAL} only (EVALSHA carries a digest, not Lua source). */
    private static void mergeLuaFindings(String cmd, List<String> av, List<SemanticError> out, boolean es) {
        if (!"EVAL".equals(cmd) || av.isEmpty()) {
            return;
        }
        String scriptBody = av.get(0);
        int numKeys = -1;
        if (av.size() >= 2) {
            try {
                numKeys = Integer.parseInt(av.get(1));
            } catch (NumberFormatException ignored) {
                numKeys = -1;
            }
        }
        out.addAll(LuaAnalyzer.analyze(scriptBody, numKeys, es));
    }

    private static void analyzeEval(String cmd, List<String> av, List<SemanticError> out, boolean es) {
        String scriptBody = av.isEmpty() ? null : av.get(0);
        if (!av.isEmpty()) {
            String script = av.get(0);
            if (script != null && script.length() > 1000) {
                out.add(new SemanticError(
                        "RDS-Script-002",
                        t(es,
                                "Consider EVALSHA with SCRIPT LOAD for long scripts to avoid resending",
                                "Considere EVALSHA con SCRIPT LOAD en scripts largos para no reenviarlos"),
                        t(es,
                                "Load once with SCRIPT LOAD, then call EVALSHA with the digest.",
                                "Cargue una vez con SCRIPT LOAD y luego llame EVALSHA con el digest."),
                        SemanticError.Severity.INFO));
            }
        }
        int numKeys = -1;
        if (av.size() >= 2) {
            try {
                numKeys = Integer.parseInt(av.get(1));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        if (scriptBody != null && !scriptBody.contains("KEYS[")) {
            boolean warnClusterKeys =
                    EVAL_CALL_WITHOUT_KEYS.matcher(scriptBody).find()
                            || ("EVAL".equals(cmd) && numKeys == 0 && scriptBody.length() > 80
                                    && scriptBody.toLowerCase(Locale.ROOT).contains("redis.call"));
            if (warnClusterKeys) {
                out.add(new SemanticError(
                        "RDS-Script-003",
                        t(es,
                                "Access Redis keys via the KEYS array in scripts for cluster compatibility",
                                "Acceda a las claves con el arreglo KEYS en scripts para compatibilidad con clúster"),
                        t(es,
                                "Replace literal key arguments with KEYS[1], KEYS[2], ... matching numkeys.",
                                "Sustituya claves literales por KEYS[1], KEYS[2], ... alineado con numkeys."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private static int indexOfIgnoreCase(List<String> list, String word) {
        for (int i = 0; i < list.size(); i++) {
            if (word.equalsIgnoreCase(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> argValues(List<RedisToken> args) {
        List<String> v = new ArrayList<>(args.size());
        for (RedisToken rt : args) {
            v.add(rt.value());
        }
        return v;
    }

    private static Double parseDoubleLoose(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Text after the first argument of {@code EVAL}/{@code EVALSHA} (script string or unquoted digest), or {@code null}
     * if not EVAL/EVALSHA or the opening string has no closing delimiter.
     */
    private static String suffixAfterEvalFirstArgument(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.stripLeading();
        if (s.isEmpty()) {
            return null;
        }
        int i;
        if (s.regionMatches(true, 0, "EVALSHA", 0, 7)) {
            i = 7;
        } else if (s.regionMatches(true, 0, "EVAL", 0, 4)) {
            i = 4;
        } else {
            return null;
        }
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= s.length()) {
            return "";
        }
        char qc = s.charAt(i);
        if (qc == '"' || qc == '\'') {
            i++;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == qc && (i == 0 || s.charAt(i - 1) != '\\')) {
                    i++;
                    return s.substring(Math.min(i, s.length()));
                }
                i++;
            }
            return null;
        }
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    /**
     * Runs {@link #detectInlineInjectionMarkers} on each non-blank line produced by {@link #splitRedisSegments},
     * matching how the rest of {@link #analyze} processes Redis input. Newlines between commands are therefore not
     * treated as injection; same-line {@code ;}, pipes, subshell markers, etc. still flag per line.
     */
    private static boolean detectCommandInjectionAcrossNewlineSeparatedCommands(String raw) {
        for (String seg : splitRedisSegments(raw)) {
            String line = seg.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (detectInlineInjectionMarkers(line)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Used on the Redis tail after the Lua script in {@code EVAL}/{@code EVALSHA}. Delegates to
     * {@link #detectInlineInjectionMarkers} so same-line {@code ;}, pipes, and {@code $(} / {@code ${} still flag,
     * while a newline before the next command is handled by analyzing that line separately (no false positive there).
     */
    private static boolean detectEvalTailInjection(String raw) {
        return detectInlineInjectionMarkers(raw);
    }

    /**
     * Heuristic for command chaining / shell-style injection within a <strong>single</strong> logical Redis line
     * (no newlines expected — callers split with {@link #splitRedisSegments} first). Outside quoted strings, flags
     * {@code ;}, backticks, pipes, and {@code $(} / {@code ${}.
     */
    private static boolean detectInlineInjectionMarkers(String raw) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (!inDouble && ch == '\'' && (i == 0 || raw.charAt(i - 1) != '\\')) {
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle && ch == '"' && (i == 0 || raw.charAt(i - 1) != '\\')) {
                inDouble = !inDouble;
                continue;
            }
            if (inSingle || inDouble) {
                continue;
            }
            if (ch == ';' || ch == '`' || ch == '|') {
                return true;
            }
            if (ch == '$' && i + 1 < raw.length() && raw.charAt(i + 1) == '(') {
                return true;
            }
            if (ch == '$' && i + 1 < raw.length() && raw.charAt(i + 1) == '{') {
                return true;
            }
        }
        return false;
    }

    private static boolean isCommandToken(RedisTokenType tok) {
        return tok == RedisTokenType.COMMAND
                || tok == RedisTokenType.STREAM_COMMAND
                || tok == RedisTokenType.TRANSACTION_COMMAND
                || tok == RedisTokenType.PUBSUB_COMMAND
                || tok == RedisTokenType.GEO_COMMAND
                || tok == RedisTokenType.HYPERLOGLOG_COMMAND
                || tok == RedisTokenType.SCRIPT_COMMAND
                || tok == RedisTokenType.BITFIELD_COMMAND;
    }

    private static String commandName(List<RedisToken> tokens) {
        for (RedisToken rt : tokens) {
            if (isCommandToken(rt.type())) {
                return rt.value();
            }
        }
        return null;
    }

    private static List<RedisToken> argsOnly(List<RedisToken> tokens) {
        List<RedisToken> args = new ArrayList<>();
        for (RedisToken rt : tokens) {
            if (isCommandToken(rt.type()) || rt.type() == RedisTokenType.EOF) {
                continue;
            }
            args.add(rt);
        }
        return args;
    }

    private static void validateArity(String cmd, List<RedisToken> args, List<SemanticError> out, boolean es) {
        int n = args.size();
        boolean bad = switch (cmd) {
            case "SET" -> n < 2;
            case "GET", "TTL", "TYPE", "HGETALL", "HKEYS", "HVALS", "LLEN", "SMEMBERS", "SCARD" -> n != 1;
            case "MSET" -> n < 2 || n % 2 != 0;
            case "MGET", "DEL", "EXISTS" -> n < 1;
            case "EXPIRE" -> n != 2;
            case "HSET", "HMSET" -> n < 3 || (n - 1) % 2 != 0;
            case "HGET", "ZRANK", "ZSCORE" -> n != 2;
            case "HMGET", "HDEL" -> n < 2;
            case "LPUSH", "RPUSH", "SADD", "SREM" -> n < 2;
            case "LPOP", "RPOP" -> n < 1 || n > 2;
            case "BLPOP", "BRPOP" -> n < 2;
            case "BRPOPLPUSH" -> n != 3;
            case "LRANGE" -> n != 3;
            case "SINTER", "SUNION", "SDIFF" -> n < 2;
            case "ZADD" -> n < 3;
            case "ZRANGE" -> n < 3;
            case "ZREM" -> n < 2;
            case "KEYS" -> n != 1;
            case "SCAN" -> n < 1;
            case "FLUSHDB", "FLUSHALL" -> n > 3;
            case "INFO", "PING" -> false;
            case "AUTH" -> n < 1 || n > 2;
            case "SELECT" -> n != 1;
            case "ACL" -> n < 1;
            default -> moduleArityBad(cmd, n);
        };

        if (bad) {
            out.add(new SemanticError(
                    "RDS-ARGS-001",
                    t(es, "Wrong number of arguments for " + cmd, "Número de argumentos incorrecto para " + cmd),
                    t(es,
                            "Check this command's arity in the Redis docs for this analyzer.",
                            "Revise la aridad del comando en la documentación de Redis para este analizador."),
                    SemanticError.Severity.ERROR));
        }
    }

    private static String firstPatternOrKeyArg(List<RedisToken> args) {
        if (args.isEmpty()) {
            return null;
        }
        RedisToken rt = args.get(0);
        if (rt.type() == RedisTokenType.PATTERN || rt.type() == RedisTokenType.KEY
                || rt.type() == RedisTokenType.VALUE || rt.type() == RedisTokenType.STRING) {
            return rt.value();
        }
        return null;
    }

    private static boolean hasConfirmAck(List<RedisToken> args) {
        if (args.isEmpty()) {
            return false;
        }
        RedisToken last = args.get(args.size() - 1);
        String v = last.value();
        return v != null && "CONFIRM".equalsIgnoreCase(v.trim());
    }

    private static boolean isSessionLikeKey(List<RedisToken> tokens) {
        boolean seenSet = false;
        for (RedisToken rt : tokens) {
            if (isCommandToken(rt.type()) && "SET".equals(rt.value())) {
                seenSet = true;
                continue;
            }
            if (seenSet && (rt.type() == RedisTokenType.KEY || rt.type() == RedisTokenType.STRING
                    || rt.type() == RedisTokenType.VALUE)) {
                String k = rt.value().toLowerCase(Locale.ROOT);
                return k.contains("session") || k.contains("token");
            }
        }
        return false;
    }

    private static boolean setHasExpiryOption(List<RedisToken> tokens) {
        for (RedisToken rt : tokens) {
            if (rt.type() != RedisTokenType.EXPIRY_FLAG) {
                continue;
            }
            String f = rt.value().toUpperCase(Locale.ROOT);
            if ("EX".equals(f) || "PX".equals(f) || "EXAT".equals(f) || "PXAT".equals(f)) {
                return true;
            }
        }
        return false;
    }
}
