package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lexer for inline Redis commands such as {@code SET user:1:name "Juan"} or {@code KEYS user:*}.
 *
 * <p>Whitespace-separated arguments; double/single-quoted strings; {@code //} line comments
 * and block comments are supported like {@link MongoDbLexer}.
 */
public final class RedisLexer {

    /** Commands this lexer recognizes (uppercase canonical names). */
    public static final Set<String> KNOWN_COMMANDS;

    private static final Set<String> EXPIRY_FLAGS = Set.of(
            "EX", "PX", "EXAT", "PXAT", "KEEPTTL");

    private static final Set<String> ZADD_HEADER_FLAGS = Set.of(
            "NX", "XX", "GT", "LT", "CH", "INCR");

    static {
        HashSet<String> k = new HashSet<>();
        Collections.addAll(k,
                "SET", "GET", "MSET", "MGET", "DEL", "EXISTS", "EXPIRE", "TTL", "TYPE",
                "HSET", "HGET", "HMSET", "HMGET", "HGETALL", "HDEL", "HKEYS", "HVALS",
                "LPUSH", "RPUSH", "LPOP", "RPOP", "BLPOP", "BRPOP", "BRPOPLPUSH",
                "LRANGE", "LLEN",
                "SADD", "SREM", "SMEMBERS", "SCARD", "SINTER", "SUNION", "SDIFF",
                "ZADD", "ZRANGE", "ZRANK", "ZREM", "ZSCORE",
                "KEYS", "SCAN", "FLUSHDB", "FLUSHALL", "INFO", "PING", "AUTH", "SELECT",
                // Streams
                "XADD", "XREAD", "XREADGROUP", "XRANGE", "XREVRANGE", "XLEN", "XACK", "XCLAIM",
                "XPENDING", "XTRIM", "XDEL", "XGROUP", "XINFO",
                // Transactions
                "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH",
                // Pub/Sub
                "PUBLISH", "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PUBSUB",
                // Geo
                "GEOADD", "GEOPOS", "GEODIST", "GEORADIUS", "GEORADIUSBYMEMBER", "GEOSEARCH",
                "GEOSEARCHSTORE", "GEOHASH",
                // HyperLogLog
                "PFADD", "PFCOUNT", "PFMERGE",
                // Scripting
                "EVAL", "EVALSHA", "SCRIPT",
                // Bitfield / bitmaps
                "BITFIELD", "BITCOUNT", "BITOP", "BITPOS", "GETBIT", "SETBIT",
                // ACL (Day 24E)
                "ACL",
                // Redis modules — RedisJSON, RediSearch, RedisGraph, RedisTimeSeries
                "JSON.GET", "JSON.SET", "JSON.DEL", "JSON.MGET", "JSON.MSET", "JSON.TYPE", "JSON.STRLEN",
                "JSON.ARRAPPEND", "JSON.ARRLEN", "JSON.NUMINCRBY", "JSON.TOGGLE", "JSON.OBJKEYS", "JSON.STRAPPEND",
                "FT.CREATE", "FT.DROPINDEX", "FT.SEARCH", "FT.AGGREGATE", "FT.EXPLAIN", "FT.INFO", "FT.ALTER",
                "GRAPH.QUERY", "GRAPH.DELETE", "GRAPH.EXPLAIN", "GRAPH.LIST",
                "TS.CREATE", "TS.ADD", "TS.MADD", "TS.GET", "TS.MGET", "TS.RANGE", "TS.MRANGE", "TS.INFO");
        KNOWN_COMMANDS = Collections.unmodifiableSet(k);
    }

    private final String input;
    private final Locale uiLocale;
    private int pos;
    private int line;
    private int column;

    public RedisLexer(String input) {
        this(input, Locale.ENGLISH);
    }

    /** @param uiLocale {@code es…} → Spanish lexer messages; otherwise English */
    public RedisLexer(String input, Locale uiLocale) {
        this.input = input != null ? input : "";
        this.uiLocale = uiLocale != null ? uiLocale : Locale.ENGLISH;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    private boolean spanishUi() {
        return uiLocale.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
    }

    private String t(String en, String es) {
        return spanishUi() ? es : en;
    }

    static RedisTokenType resolveCommandTokenType(String cmd) {
        return switch (cmd) {
            case "XADD", "XREAD", "XREADGROUP", "XRANGE", "XREVRANGE", "XLEN", "XACK", "XCLAIM",
                    "XPENDING", "XTRIM", "XDEL", "XGROUP", "XINFO" -> RedisTokenType.STREAM_COMMAND;
            case "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH" -> RedisTokenType.TRANSACTION_COMMAND;
            case "PUBLISH", "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PUBSUB" ->
                    RedisTokenType.PUBSUB_COMMAND;
            case "GEOADD", "GEOPOS", "GEODIST", "GEORADIUS", "GEORADIUSBYMEMBER", "GEOSEARCH",
                    "GEOSEARCHSTORE", "GEOHASH" -> RedisTokenType.GEO_COMMAND;
            case "PFADD", "PFCOUNT", "PFMERGE" -> RedisTokenType.HYPERLOGLOG_COMMAND;
            case "EVAL", "EVALSHA", "SCRIPT" -> RedisTokenType.SCRIPT_COMMAND;
            case "BITFIELD", "BITCOUNT", "BITOP", "BITPOS", "GETBIT", "SETBIT" ->
                    RedisTokenType.BITFIELD_COMMAND;
            default -> RedisTokenType.COMMAND;
        };
    }

    /**
     * Tokenizes one Redis command line into {@link RedisToken} values ending with {@link RedisTokenType#EOF}.
     *
     * @throws LexException on invalid escapes or unterminated quotes
     */
    public List<RedisToken> tokenize() {
        List<RedisToken> out = new ArrayList<>(16);
        skipWsAndComments();
        if (isEof()) {
            out.add(tok(RedisTokenType.EOF, ""));
            return out;
        }

        int cl = line;
        int cc = column;
        String cmdRaw = readCommandWord();
        if (cmdRaw.isEmpty()) {
            throw new LexException(t("Expected a Redis command", "Se esperaba un comando Redis"), cl, cc);
        }
        String cmd = cmdRaw.toUpperCase(Locale.ROOT);
        out.add(new RedisToken(resolveCommandTokenType(cmd), cmd, cl, cc));

        List<RawArg> rawArgs = new ArrayList<>();
        while (true) {
            skipWsAndComments();
            if (isEof() || peek() == ';') {
                break;
            }
            rawArgs.add(readRawArg());
        }
        skipWsAndComments();
        if (!isEof() && peek() == ';') {
            advance();
        }
        skipWsAndComments();
        if (!isEof()) {
            throw new LexException(
                    t("Unexpected characters after command", "Caracteres inesperados después del comando"),
                    line,
                    column);
        }

        classifyArguments(cmd, rawArgs, out);
        out.add(tok(RedisTokenType.EOF, ""));
        return out;
    }

    private void classifyArguments(String cmd, List<RawArg> rawArgs, List<RedisToken> out) {
        switch (cmd) {
            case "SET" -> classifySet(rawArgs, out);
            case "MSET" -> classifyKeyValuePairs(rawArgs, out, true);
            case "MGET", "DEL", "EXISTS" -> classifyAllKeys(rawArgs, out);
            case "GET", "TTL", "TYPE" -> classifyFixedKeys(rawArgs, out, 1);
            case "EXPIRE" -> classifyExpire(rawArgs, out);
            case "HSET", "HMSET" -> classifyHset(rawArgs, out);
            case "HGET" -> classifyFixedKeyField(rawArgs, out);
            case "HMGET" -> classifyHmget(rawArgs, out);
            case "HGETALL", "HKEYS", "HVALS" -> classifyFixedKeys(rawArgs, out, 1);
            case "HDEL" -> classifyHdel(rawArgs, out);
            case "LPUSH", "RPUSH" -> classifyPush(rawArgs, out);
            case "LPOP", "RPOP" -> classifyPop(rawArgs, out);
            case "BLPOP", "BRPOP" -> classifyBlpop(rawArgs, out);
            case "BRPOPLPUSH" -> classifyBrpoplpush(rawArgs, out);
            case "LRANGE" -> classifyLrange(rawArgs, out);
            case "LLEN" -> classifyFixedKeys(rawArgs, out, 1);
            case "SADD", "SREM" -> classifySetMembers(rawArgs, out);
            case "SMEMBERS", "SCARD" -> classifyFixedKeys(rawArgs, out, 1);
            case "SINTER", "SUNION", "SDIFF" -> classifyAllKeys(rawArgs, out);
            case "ZADD" -> classifyZadd(rawArgs, out);
            case "ZRANGE" -> classifyZrange(rawArgs, out);
            case "ZRANK", "ZSCORE" -> classifyFixedKeyMember(rawArgs, out);
            case "ZREM" -> classifyZrem(rawArgs, out);
            case "KEYS" -> classifyKeys(rawArgs, out);
            case "SCAN" -> classifyScan(rawArgs, out);
            case "FLUSHDB", "FLUSHALL", "INFO", "PING", "ACL" -> classifyLoose(rawArgs, out);
            case "AUTH" -> classifyAuth(rawArgs, out);
            case "SELECT" -> classifySelect(rawArgs, out);
            case "XADD" -> classifyXadd(rawArgs, out);
            case "PFADD" -> classifyPfadd(rawArgs, out);
            case "PFCOUNT" -> classifyAllKeys(rawArgs, out);
            case "PFMERGE" -> classifyPfmerge(rawArgs, out);
            case "EVAL", "EVALSHA" -> classifyEval(rawArgs, out);
            case "BITOP" -> classifyBitop(rawArgs, out);
            default -> classifyLoose(rawArgs, out);
        }
    }

    private void classifyXadd(List<RawArg> rawArgs, List<RedisToken> out) {
        if (rawArgs.isEmpty()) {
            return;
        }
        int i = 0;
        out.add(rawArgs.get(i++).toToken(RedisTokenType.KEY));
        if (i < rawArgs.size() && "NOMKSTREAM".equals(rawArgs.get(i).asUpperWord())) {
            out.add(rawArgs.get(i++).toToken(RedisTokenType.UNKNOWN));
        }
        if (i < rawArgs.size() && "MAXLEN".equals(rawArgs.get(i).asUpperWord())) {
            out.add(rawArgs.get(i++).toToken(RedisTokenType.UNKNOWN));
            if (i < rawArgs.size()) {
                String maybeEq = rawArgs.get(i).value();
                if ("=".equals(maybeEq) || "~".equals(maybeEq)) {
                    out.add(rawArgs.get(i++).toToken(RedisTokenType.UNKNOWN));
                }
            }
            if (i < rawArgs.size()) {
                emitNumberOrUnknown(rawArgs.get(i++), out);
            }
        }
        if (i < rawArgs.size()) {
            emitRawAsUnknownOrNumber(rawArgs.get(i++), out);
        }
        while (i < rawArgs.size()) {
            out.add(rawArgs.get(i++).toToken(RedisTokenType.FIELD));
            if (i < rawArgs.size()) {
                emitValueToken(rawArgs.get(i++), out);
            }
        }
    }

    private void classifyPfadd(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            emitValueToken(rawArgs.get(i), out);
        }
    }

    private void classifyPfmerge(List<RawArg> rawArgs, List<RedisToken> out) {
        for (RawArg r : rawArgs) {
            out.add(r.toToken(RedisTokenType.KEY));
        }
    }

    private void classifyEval(List<RawArg> rawArgs, List<RedisToken> out) {
        if (rawArgs.isEmpty()) {
            return;
        }
        emitValueToken(rawArgs.get(0), out);
        int numKeys = -1;
        if (rawArgs.size() > 1) {
            try {
                numKeys = Integer.parseInt(rawArgs.get(1).value());
            } catch (NumberFormatException ignored) {
                numKeys = -1;
            }
            emitNumberOrUnknown(rawArgs.get(1), out);
        }
        for (int i = 2; i < rawArgs.size(); i++) {
            if (numKeys >= 0 && i < 2 + numKeys) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.KEY));
            } else {
                emitValueToken(rawArgs.get(i), out);
            }
        }
    }

    private void classifyBitop(List<RawArg> rawArgs, List<RedisToken> out) {
        for (RawArg r : rawArgs) {
            out.add(r.toToken(RedisTokenType.UNKNOWN));
        }
    }

    private void classifyBlpop(List<RawArg> rawArgs, List<RedisToken> out) {
        if (rawArgs.isEmpty()) {
            return;
        }
        for (int i = 0; i < rawArgs.size() - 1; i++) {
            out.add(rawArgs.get(i).toToken(RedisTokenType.KEY));
        }
        emitNumberOrUnknown(rawArgs.get(rawArgs.size() - 1), out);
    }

    private void classifyBrpoplpush(List<RawArg> rawArgs, List<RedisToken> out) {
        if (rawArgs.size() >= 3) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
            out.add(rawArgs.get(1).toToken(RedisTokenType.KEY));
            emitNumberOrUnknown(rawArgs.get(2), out);
        } else {
            classifyLoose(rawArgs, out);
        }
    }

    private void classifySet(List<RawArg> rawArgs, List<RedisToken> out) {
        if (rawArgs.size() < 2) {
            for (RawArg r : rawArgs) {
                emitRawAsUnknownOrNumber(r, out);
            }
            return;
        }
        out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        emitValueToken(rawArgs.get(1), out);
        int i = 2;
        while (i < rawArgs.size()) {
            String w = rawArgs.get(i).asUpperWord();
            if (w != null && EXPIRY_FLAGS.contains(w)) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.EXPIRY_FLAG));
                i++;
                if (w.equals("KEEPTTL")) {
                    continue;
                }
                if (i < rawArgs.size()) {
                    emitNumberOrUnknown(rawArgs.get(i), out);
                    i++;
                }
                continue;
            }
            emitRawAsUnknownOrNumber(rawArgs.get(i), out);
            i++;
        }
    }

    private void classifyKeyValuePairs(List<RawArg> rawArgs, List<RedisToken> out, boolean keyFirst) {
        for (int i = 0; i < rawArgs.size(); i++) {
            boolean isKey = keyFirst ? (i % 2 == 0) : (i % 2 == 1);
            if (isKey) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.KEY));
            } else {
                emitValueToken(rawArgs.get(i), out);
            }
        }
    }

    private void classifyAllKeys(List<RawArg> rawArgs, List<RedisToken> out) {
        for (RawArg r : rawArgs) {
            out.add(r.toToken(RedisTokenType.KEY));
        }
    }

    private void classifyFixedKeys(List<RawArg> rawArgs, List<RedisToken> out, int n) {
        for (int i = 0; i < rawArgs.size(); i++) {
            if (i < n) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.KEY));
            } else {
                emitRawAsUnknownOrNumber(rawArgs.get(i), out);
            }
        }
    }

    private void classifyExpire(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        if (rawArgs.size() > 1) {
            emitNumberOrUnknown(rawArgs.get(1), out);
        }
        for (int i = 2; i < rawArgs.size(); i++) {
            emitRawAsUnknownOrNumber(rawArgs.get(i), out);
        }
    }

    private void classifyHset(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i += 2) {
            out.add(rawArgs.get(i).toToken(RedisTokenType.FIELD));
            if (i + 1 < rawArgs.size()) {
                emitValueToken(rawArgs.get(i + 1), out);
            }
        }
    }

    private void classifyFixedKeyField(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        if (rawArgs.size() > 1) {
            out.add(rawArgs.get(1).toToken(RedisTokenType.FIELD));
        }
        for (int i = 2; i < rawArgs.size(); i++) {
            emitRawAsUnknownOrNumber(rawArgs.get(i), out);
        }
    }

    private void classifyHmget(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            out.add(rawArgs.get(i).toToken(RedisTokenType.FIELD));
        }
    }

    private void classifyHdel(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            out.add(rawArgs.get(i).toToken(RedisTokenType.FIELD));
        }
    }

    private void classifyPush(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            emitValueToken(rawArgs.get(i), out);
        }
    }

    private void classifyPop(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            emitNumberOrUnknown(rawArgs.get(i), out);
        }
    }

    private void classifyLrange(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            emitNumberOrUnknown(rawArgs.get(i), out);
        }
    }

    private void classifySetMembers(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            emitValueToken(rawArgs.get(i), out);
        }
    }

    private void classifyZadd(List<RawArg> rawArgs, List<RedisToken> out) {
        if (rawArgs.isEmpty()) {
            return;
        }
        out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        int i = 1;
        while (i < rawArgs.size()) {
            String w = rawArgs.get(i).asUpperWord();
            if (w != null && ZADD_HEADER_FLAGS.contains(w)) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.UNKNOWN));
                i++;
                continue;
            }
            emitNumberOrUnknown(rawArgs.get(i), out);
            i++;
            if (i < rawArgs.size()) {
                emitValueToken(rawArgs.get(i), out);
                i++;
            }
        }
    }

    private void classifyZrange(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            String w = rawArgs.get(i).asUpperWord();
            if (w != null && (w.equals("BYSCORE") || w.equals("BYLEX") || w.equals("REV")
                    || w.equals("LIMIT"))) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.UNKNOWN));
                continue;
            }
            emitNumberOrUnknown(rawArgs.get(i), out);
        }
    }

    private void classifyFixedKeyMember(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            emitValueToken(rawArgs.get(i), out);
        }
    }

    private void classifyZrem(List<RawArg> rawArgs, List<RedisToken> out) {
        if (!rawArgs.isEmpty()) {
            out.add(rawArgs.get(0).toToken(RedisTokenType.KEY));
        }
        for (int i = 1; i < rawArgs.size(); i++) {
            emitValueToken(rawArgs.get(i), out);
        }
    }

    private void classifyKeys(List<RawArg> rawArgs, List<RedisToken> out) {
        for (RawArg r : rawArgs) {
            out.add(r.toToken(RedisTokenType.PATTERN));
        }
    }

    private void classifyScan(List<RawArg> rawArgs, List<RedisToken> out) {
        boolean wantMatchPattern = false;
        boolean wantCountNumber = false;
        for (int i = 0; i < rawArgs.size(); i++) {
            String w = rawArgs.get(i).asUpperWord();
            if (wantMatchPattern) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.PATTERN));
                wantMatchPattern = false;
                continue;
            }
            if (wantCountNumber) {
                emitNumberOrUnknown(rawArgs.get(i), out);
                wantCountNumber = false;
                continue;
            }
            if ("MATCH".equals(w)) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.UNKNOWN));
                wantMatchPattern = true;
                continue;
            }
            if ("COUNT".equals(w)) {
                out.add(rawArgs.get(i).toToken(RedisTokenType.UNKNOWN));
                wantCountNumber = true;
                continue;
            }
            if (i == 0) {
                emitNumberOrUnknown(rawArgs.get(i), out);
            } else {
                emitRawAsUnknownOrNumber(rawArgs.get(i), out);
            }
        }
    }

    private void classifyAuth(List<RawArg> rawArgs, List<RedisToken> out) {
        for (RawArg r : rawArgs) {
            emitValueToken(r, out);
        }
    }

    private void classifySelect(List<RawArg> rawArgs, List<RedisToken> out) {
        for (RawArg r : rawArgs) {
            emitNumberOrUnknown(r, out);
        }
    }

    private void classifyLoose(List<RawArg> rawArgs, List<RedisToken> out) {
        for (RawArg r : rawArgs) {
            emitRawAsUnknownOrNumber(r, out);
        }
    }

    private static void emitValueToken(RawArg r, List<RedisToken> out) {
        if (r.kind == RawKind.NUMBER) {
            out.add(r.toToken(RedisTokenType.NUMBER));
        } else if (r.kind == RawKind.QUOTED) {
            out.add(r.toToken(RedisTokenType.STRING));
        } else {
            out.add(r.toToken(RedisTokenType.VALUE));
        }
    }

    private static void emitNumberOrUnknown(RawArg r, List<RedisToken> out) {
        if (r.kind == RawKind.NUMBER) {
            out.add(r.toToken(RedisTokenType.NUMBER));
        } else {
            out.add(r.toToken(RedisTokenType.UNKNOWN));
        }
    }

    private static void emitRawAsUnknownOrNumber(RawArg r, List<RedisToken> out) {
        if (r.kind == RawKind.NUMBER) {
            out.add(r.toToken(RedisTokenType.NUMBER));
        } else {
            out.add(r.toToken(RedisTokenType.UNKNOWN));
        }
    }

    private String readNumberLexeme() {
        int l = line;
        int c = column;
        int start = pos;
        if (peek() == '-') {
            advance();
        }
        if (!isEof() && Character.isDigit(peek())) {
            while (!isEof() && Character.isDigit(peek())) {
                advance();
            }
        } else {
            throw new LexException(t("Invalid numeric literal", "Literal numérico inválido"), l, c);
        }
        return input.substring(start, pos);
    }

    private static boolean looksNumeric(String w) {
        if (w == null || w.isEmpty()) {
            return false;
        }
        int i = 0;
        if (w.charAt(0) == '-') {
            if (w.length() == 1) {
                return false;
            }
            i = 1;
        }
        for (; i < w.length(); i++) {
            if (!Character.isDigit(w.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String readStringToken() {
        int l = line;
        int c = column;
        char quote = peek();
        advance();
        StringBuilder sb = new StringBuilder();
        while (!isEof() && peek() != quote) {
            if (peek() == '\\') {
                advance();
                if (!isEof()) {
                    sb.append(escapeChar(peek()));
                    advance();
                }
                continue;
            }
            sb.append(peek());
            advance();
        }
        if (isEof()) {
            throw new LexException(t("Unclosed string", "Cadena sin cerrar"), l, c);
        }
        advance();
        return sb.toString();
    }

    private static char escapeChar(char ch) {
        return switch (ch) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '\\', '"', '\'' -> ch;
            default -> ch;
        };
    }

    /** First token: letters and underscore only (Redis command name). */
    private String readCommandWord() {
        if (isEof()) {
            throw new LexException(t("Expected a Redis command", "Se esperaba un comando Redis"), line, column);
        }
        char c0 = peek();
        if (!(Character.isLetter(c0) || c0 == '_')) {
            throw new LexException(t("Invalid command name", "Nombre de comando inválido"), line, column);
        }
        int start = pos;
        while (!isEof()) {
            char ch = peek();
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '.')) {
                break;
            }
            advance();
        }
        return input.substring(start, pos);
    }

    private String readWord(String enWhat, String esWhat) {
        if (isEof()) {
            throw new LexException(t("Expected " + enWhat, "Se esperaba " + esWhat), line, column);
        }
        char c0 = peek();
        if (!(Character.isLetter(c0) || c0 == '_' || c0 == '*' || c0 == '?' || c0 == '[' || c0 == ']'
                || Character.isDigit(c0) || c0 == ':' || c0 == '.' || c0 == '-' || c0 == '@'
                || c0 == '=' || c0 == '~' || c0 == '+' || c0 == '$')) {
            throw new LexException(t("Expected " + enWhat, "Se esperaba " + esWhat), line, column);
        }
        int start = pos;
        while (!isEof()) {
            char ch = peek();
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                break;
            }
            if (ch == ';') {
                break;
            }
            advance();
        }
        return input.substring(start, pos);
    }

    private void skipWsAndComments() {
        while (!isEof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
                continue;
            }
            if (c == '/' && pos + 1 < input.length()) {
                char n = input.charAt(pos + 1);
                if (n == '/') {
                    advance();
                    advance();
                    while (!isEof() && peek() != '\n') {
                        advance();
                    }
                    continue;
                }
                if (n == '*') {
                    advance();
                    advance();
                    while (pos + 1 < input.length()) {
                        if (peek() == '*' && input.charAt(pos + 1) == '/') {
                            advance();
                            advance();
                            break;
                        }
                        advance();
                    }
                    if (pos >= input.length()) {
                        throw new LexException(
                                t("Unclosed block comment", "Comentario de bloque sin cerrar"),
                                line,
                                column);
                    }
                    continue;
                }
            }
            break;
        }
    }

    private char peek() {
        return isEof() ? '\0' : input.charAt(pos);
    }

    private boolean isEof() {
        return pos >= input.length();
    }

    private void advance() {
        if (isEof()) {
            return;
        }
        char ch = input.charAt(pos++);
        if (ch == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }

    private RedisToken tok(RedisTokenType type, String value) {
        return new RedisToken(type, value, line, column);
    }

    private enum RawKind { WORD, QUOTED, NUMBER }

    private record RawArg(RawKind kind, String value, int line, int column) {

        RedisToken toToken(RedisTokenType type) {
            return new RedisToken(type, value, line, column);
        }

        String asUpperWord() {
            if (kind != RawKind.WORD) {
                return null;
            }
            return value.toUpperCase(Locale.ROOT);
        }
    }

    private RawArg readRawArg() {
        int l = line;
        int c = column;
        char ch = peek();
        if (ch == '"' || ch == '\'') {
            String s = readStringToken();
            return new RawArg(RawKind.QUOTED, s, l, c);
        }
        if (ch == '-' || Character.isDigit(ch)) {
            String n = readNumberLexeme();
            return new RawArg(RawKind.NUMBER, n, l, c);
        }
        String w = readWord("an argument", "un argumento");
        if (looksNumeric(w)) {
            return new RawArg(RawKind.NUMBER, w, l, c);
        }
        return new RawArg(RawKind.WORD, w, l, c);
    }

    public static final class LexException extends RuntimeException {
        private final int line;
        private final int column;

        public LexException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }

        public int line() {
            return line;
        }

        public int column() {
            return column;
        }
    }
}
