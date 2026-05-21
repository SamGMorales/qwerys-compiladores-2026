package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lexer for MongoDB shell-style calls such as
 * {@code db.users.find({email: "a@b.com"})}, {@code insertOne}, {@code updateMany}, {@code aggregate}.
 *
 * <p>Parentheses around method arguments are consumed but not emitted as tokens.
 */
public final class MongoDbLexer {

    /**
     * Aggregation pipeline stages recognized for tooling / diagnostics (keys include {@code $}).
     */
    public static final Set<String> PIPELINE_STAGES = Collections.unmodifiableSet(new HashSet<>(List.of(
            "$lookup", "$unwind", "$group", "$project", "$sort", "$limit", "$skip", "$count",
            "$addFields", "$replaceRoot", "$replaceWith", "$merge", "$out", "$facet", "$bucket",
            "$bucketAuto", "$sortByCount", "$sample", "$geoNear", "$graphLookup", "$unionWith",
            "$match", "$indexStats")));

    /** Array update / query operators (keys include {@code $}). */
    public static final Set<String> ARRAY_OPERATORS = Collections.unmodifiableSet(new HashSet<>(List.of(
            "$elemMatch", "$all", "$size", "$slice", "$push", "$pull", "$pullAll",
            "$addToSet", "$pop", "$each", "$position")));

    /** Text search and geospatial query operators (keys include {@code $}). */
    public static final Set<String> TEXT_AND_GEO_OPERATORS = Collections.unmodifiableSet(new HashSet<>(List.of(
            "$text", "$search", "$language", "$caseSensitive",
            "$near", "$nearSphere", "$geoWithin", "$geoIntersects",
            "$box", "$polygon", "$center", "$centerSphere")));

    /** Aggregation expression operators (keys include {@code $}). */
    public static final Set<String> EXPRESSION_OPERATORS = Collections.unmodifiableSet(new HashSet<>(List.of(
            "$expr", "$cond", "$switch", "$ifNull", "$map", "$filter", "$reduce",
            "$let", "$setIntersection", "$setUnion", "$setDifference")));

    /** All advanced operators this lexer/analyzer explicitly catalogues. */
    public static final Set<String> ALL_CATALOGUED_OPERATORS;

    /**
     * Shell cursor chain methods after the primary {@code db.collection.method(...)} call
     * (mongosh / Node driver fluent API).
     */
    public static final Set<String> CHAIN_METHODS = Collections.unmodifiableSet(new HashSet<>(List.of(
            "limit", "skip", "sort", "project", "hint", "batchsize", "maxtimems", "maxawaittimems",
            "allowdiskuse", "comment", "collation", "readpref", "max", "min", "showrecordid",
            "returnkey", "nocursortimeout", "explain", "count", "countdocuments", "size", "itcount",
            "hasnext", "next", "trynext", "close", "toarray", "pretty", "foreach", "map", "filter",
            "rewind", "addcursorflag", "showhint", "isclosed", "maxscan", "tailable", "oplogreplay",
            "exhaust", "partial", "modifiers")));

    static {
        HashSet<String> all = new HashSet<>();
        all.addAll(PIPELINE_STAGES);
        all.addAll(ARRAY_OPERATORS);
        all.addAll(TEXT_AND_GEO_OPERATORS);
        all.addAll(EXPRESSION_OPERATORS);
        all.add("$where");
        all.add("$function");
        all.add("$regex");
        all.add("$exists");
        all.add("$in");
        ALL_CATALOGUED_OPERATORS = Collections.unmodifiableSet(all);
    }

    private final String input;
    private final Locale uiLocale;
    private int pos;
    private int line;
    private int column;

    public MongoDbLexer(String input) {
        this(input, Locale.ENGLISH);
    }

    /**
     * @param uiLocale UI locale ({@code es} → Spanish lexer messages; otherwise English)
     */
    public MongoDbLexer(String input, Locale uiLocale) {
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

    /**
     * Tokenizes {@code db.<collection>.<method>(...)} and nested BSON-like literals.
     *
     * @throws LexException on unterminated strings or malformed input
     */
    public List<NoSqlToken> tokenize() {
        List<NoSqlToken> out = new ArrayList<>(64);
        skipWsAndComments();
        if (isEof()) {
            out.add(tok(NoSqlTokenType.EOF, ""));
            return out;
        }

        expectWord("db", t("Expected the 'db.' prefix", "Se esperaba el prefijo 'db.'"));
        out.add(tok(NoSqlTokenType.FIELD_NAME, "db"));

        expectChar('.', t("Expected '.' after 'db'", "Se esperaba '.' después de 'db'"));
        skipWsAndComments();
        if (peekIdentifierLowercaseEquals("createcollection")) {
            readCreateCollectionShellCall(out);
        } else {
            String collection = readIdentifier("collection name", "nombre de colección");
            out.add(tok(NoSqlTokenType.COLLECTION_NAME, collection));

            expectChar('.', t("Expected '.' after the collection name", "Se esperaba '.' después del nombre de colección"));
            NoSqlTokenType methodType = readMethodName();
            String methodLexeme = methodLexeme(methodType);
            out.add(tok(methodType, methodLexeme));

            skipWsAndComments();
            expectChar('(', t("Expected '(' after the method name", "Se esperaba '(' después del nombre del método"));

            readMethodArguments(out);
            readMethodChain(out);
        }

        skipWsAndComments();
        if (peek() == ';') {
            advance();
        }
        skipWsAndComments();
        if (!isEof()) {
            throw new LexException(
                    t("Unexpected characters after end of call", "Caracteres inesperados después del fin de la llamada"),
                    line,
                    column);
        }

        out.add(tok(NoSqlTokenType.EOF, ""));
        return out;
    }

    private static String methodLexeme(NoSqlTokenType methodType) {
        return switch (methodType) {
            case FIND -> "find";
            case AGGREGATE -> "aggregate";
            case MAPREDUCE -> "mapreduce";
            case INSERT -> "insert";
            case UPDATE -> "update";
            case DELETE -> "delete";
            case DROP -> "drop";
            case CREATE -> "create";
            case CREATE_DB_COLLECTION -> "createCollection";
            case WATCH -> "watch";
            default -> methodType.name().toLowerCase(Locale.ROOT);
        };
    }

    private boolean peekIdentifierLowercaseEquals(String want) {
        int savePos = pos;
        int saveLine = line;
        int saveCol = column;
        skipWsAndComments();
        if (isEof() || !isIdentifierStart(peek())) {
            pos = savePos;
            line = saveLine;
            column = saveCol;
            return false;
        }
        int start = pos;
        while (!isEof() && isIdentifierPart(peek())) {
            advance();
        }
        String got = input.substring(start, pos).toLowerCase(Locale.ROOT);
        pos = savePos;
        line = saveLine;
        column = saveCol;
        return want.equalsIgnoreCase(got);
    }

    /**
     * {@code db.createCollection("name", { validator: { $jsonSchema: { ... } } })} — the collection name is the first
     * string argument.
     */
    private void readCreateCollectionShellCall(List<NoSqlToken> out) {
        int methodLine = line;
        int methodCol = column;
        String kw = readIdentifier("createCollection", "createCollection");
        if (!"createcollection".equalsIgnoreCase(kw)) {
            throw new LexException(
                    t("Expected createCollection", "Se esperaba createCollection"),
                    methodLine,
                    methodCol);
        }
        skipWsAndComments();
        expectChar('(', t("Expected '(' after createCollection", "Se esperaba '(' después de createCollection"));
        skipWsAndComments();
        if (peek() == ')') {
            throw new LexException(
                    t("createCollection requires a collection name string", "createCollection requiere el nombre como cadena"),
                    line,
                    column);
        }
        NoSqlToken nameTok = readStringToken();
        out.add(new NoSqlToken(NoSqlTokenType.COLLECTION_NAME, nameTok.value(), nameTok.line(), nameTok.column()));
        out.add(tok(NoSqlTokenType.CREATE_DB_COLLECTION, "createCollection", methodLine, methodCol));
        skipWsAndComments();
        if (peek() == ')') {
            advance();
            return;
        }
        if (peek() == ',') {
            out.add(tok(NoSqlTokenType.COMMA, ","));
            advance();
            skipWsAndComments();
            readValueTokens(out);
        }
        skipWsAndComments();
        expectChar(')', t("Expected ')' after createCollection arguments", "Se esperaba ')' tras createCollection"));
    }

    private NoSqlTokenType readMethodName() {
        int start = pos;
        if (!isIdentifierStart(peek())) {
            throw new LexException(t("Invalid method name", "Nombre de método inválido"), line, column);
        }
        while (!isEof() && isIdentifierPart(peek())) {
            advance();
        }
        String name = input.substring(start, pos).toLowerCase();
        return switch (name) {
            case "find", "findone" -> NoSqlTokenType.FIND;
            case "insert", "insertone", "insertmany" -> NoSqlTokenType.INSERT;
            case "update", "updateone", "updatemany", "replaceone" -> NoSqlTokenType.UPDATE;
            case "delete", "deleteone", "deletemany", "remove" -> NoSqlTokenType.DELETE;
            case "aggregate" -> NoSqlTokenType.AGGREGATE;
            case "mapreduce" -> NoSqlTokenType.MAPREDUCE;
            case "drop" -> NoSqlTokenType.DROP;
            case "createindex", "createindexes", "createview" -> NoSqlTokenType.CREATE;
            case "watch" -> NoSqlTokenType.WATCH;
            default -> throw new LexException(
                    t("Unrecognized MongoDB method: " + name, "Método MongoDB no reconocido: " + name),
                    line,
                    column);
        };
    }

    private void readMethodArguments(List<NoSqlToken> out) {
        skipWsAndComments();
        if (peek() == ')') {
            advance();
            return;
        }
        readValueTokens(out);
        skipWsAndComments();
        while (peek() == ',') {
            out.add(tok(NoSqlTokenType.COMMA, ","));
            advance();
            skipWsAndComments();
            readValueTokens(out);
            skipWsAndComments();
        }
        expectChar(')', t("Expected ')' to close the method call", "Se esperaba ')' para cerrar la llamada al método"));
    }

    /**
     * Parses fluent cursor chains: {@code .limit(100).sort({ a: 1 })} after the primary shell call.
     */
    private void readMethodChain(List<NoSqlToken> out) {
        skipWsAndComments();
        while (!isEof() && peek() == '.') {
            advance();
            skipWsAndComments();
            int methodLine = line;
            int methodCol = column;
            String name = readChainMethodName();
            out.add(tok(NoSqlTokenType.CHAIN_METHOD, name, methodLine, methodCol));
            skipWsAndComments();
            expectChar('(', t("Expected '(' after chained method", "Se esperaba '(' después del método encadenado"));
            readMethodArguments(out);
            skipWsAndComments();
        }
    }

    private String readChainMethodName() {
        if (!isIdentifierStart(peek())) {
            throw new LexException(
                    t("Invalid chained method name", "Nombre de método encadenado inválido"),
                    line,
                    column);
        }
        int start = pos;
        while (!isEof() && isIdentifierPart(peek())) {
            advance();
        }
        String name = input.substring(start, pos);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!CHAIN_METHODS.contains(lower)) {
            throw new LexException(
                    t("Unrecognized chained method: " + name + ". Supported: limit, skip, sort, project, hint, …",
                            "Método encadenado no reconocido: " + name + ". Soportados: limit, skip, sort, project, hint, …"),
                    line,
                    column);
        }
        return lower;
    }

    private void readValueTokens(List<NoSqlToken> out) {
        skipWsAndComments();
        char c = peek();
        if (c == '{') {
            readObjectTokens(out);
        } else if (c == '[') {
            readArrayTokens(out);
        } else if (c == '"' || c == '\'') {
            out.add(readStringToken());
        } else if (c == '-' || Character.isDigit(c)) {
            out.add(readNumberToken());
        } else if (isIdentifierStart(c)) {
            readIdentifierOrConstructorOrKeyword(out);
        } else if (c == '/') {
            out.add(readRegexLiteralToken());
        } else {
            throw new LexException(
                    t("Invalid BSON value at current position", "Valor BSON inválido en la posición actual"),
                    line,
                    column);
        }
    }

    /**
     * Shell helpers like {@code ObjectId("...")} / {@code ISODate("...")} become a single opaque STRING token.
     */
    private void readIdentifierOrConstructorOrKeyword(List<NoSqlToken> out) {
        int markPos = pos;
        int markLine = line;
        int markCol = column;
        String w = readIdentifier("identifier", "identificador");
        skipWsAndComments();
        if (!isEof() && peek() == '(') {
            pos = markPos;
            line = markLine;
            column = markCol;
            String opaque = readShellConstructorLiteral();
            out.add(new NoSqlToken(NoSqlTokenType.STRING, opaque, markLine, markCol));
            return;
        }
        switch (w.toLowerCase(Locale.ROOT)) {
            case "true", "false" -> out.add(new NoSqlToken(NoSqlTokenType.BOOLEAN, w.toLowerCase(Locale.ROOT), markLine, markCol));
            case "null" -> out.add(new NoSqlToken(NoSqlTokenType.NULL, "null", markLine, markCol));
            default -> throw new LexException(
                    t("Unknown literal keyword: " + w, "Palabra literal desconocida: " + w),
                    markLine,
                    markCol);
        }
    }

    private String readShellConstructorLiteral() {
        int outerStart = pos;
        readIdentifier("constructor", "constructor");
        skipWsAndComments();
        readBalancedParenSegment();
        return input.substring(outerStart, pos);
    }

    /** Reads {@code (...)} with nesting and rudimentary string/quote awareness (starts at '('). */
    private String readBalancedParenSegment() {
        if (isEof() || peek() != '(') {
            throw new LexException(t("Expected '('", "Se esperaba '('"), line, column);
        }
        int depth = 0;
        int segmentStart = pos;
        while (!isEof()) {
            char ch = peek();
            if (ch == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '/') {
                advance();
                advance();
                while (!isEof() && peek() != '\n') {
                    advance();
                }
                continue;
            }
            if (ch == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '*') {
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
                continue;
            }
            if (ch == '"' || ch == '\'') {
                char q = ch;
                advance();
                while (!isEof() && peek() != q) {
                    if (peek() == '\\') {
                        advance();
                    }
                    advance();
                }
                if (isEof()) {
                    throw new LexException(
                            t("Unclosed string inside () call", "Cadena sin cerrar dentro de llamada ()"),
                            line,
                            column);
                }
                advance();
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            advance();
            if (depth == 0) {
                break;
            }
        }
        if (depth != 0) {
            throw new LexException(t("Unclosed parenthesis", "Paréntesis sin cerrar"), line, column);
        }
        return input.substring(segmentStart, pos);
    }

    private NoSqlToken readRegexLiteralToken() {
        int l = line;
        int c = column;
        int start = pos;
        advance(); // /
        while (!isEof() && peek() != '/') {
            if (peek() == '\\' && pos + 1 < input.length()) {
                advance();
                advance();
                continue;
            }
            advance();
        }
        if (isEof()) {
            throw new LexException(
                    t("Unclosed regular expression literal", "Literal de expresión regular sin cerrar"),
                    l,
                    c);
        }
        advance(); // closing /
        while (!isEof() && Character.isLetter(peek())) {
            advance(); // flags i,m,x,... counted inside substring(start,pos)
        }
        String full = input.substring(start, pos);
        return new NoSqlToken(NoSqlTokenType.STRING, full, l, c);
    }

    private void readObjectTokens(List<NoSqlToken> out) {
        int l = line;
        int c = column;
        expectChar('{', null);
        out.add(tok(NoSqlTokenType.BRACE_OPEN, "{", l, c));
        skipWsAndComments();
        if (peek() == '}') {
            int l2 = line;
            int c2 = column;
            advance();
            out.add(tok(NoSqlTokenType.BRACE_CLOSE, "}", l2, c2));
            return;
        }
        while (true) {
            readObjectKey(out);
            skipWsAndComments();
            expectChar(':', t("Expected ':' after object key", "Se esperaba ':' después de la clave del objeto"));
            out.add(tok(NoSqlTokenType.COLON, ":"));
            readValueTokens(out);
            skipWsAndComments();
            if (peek() == '}') {
                break;
            }
            if (peek() != ',') {
                throw new LexException(
                        t("Expected ',' or '}' in BSON object", "Se esperaba ',' o '}' en objeto BSON"),
                        line,
                        column);
            }
            out.add(tok(NoSqlTokenType.COMMA, ","));
            advance();
            skipWsAndComments();
        }
        int le = line;
        int ce = column;
        advance(); // }
        out.add(tok(NoSqlTokenType.BRACE_CLOSE, "}", le, ce));
    }

    private void readObjectKey(List<NoSqlToken> out) {
        skipWsAndComments();
        char c = peek();
        if (c == '"' || c == '\'') {
            NoSqlToken s = readStringToken();
            classifyKey(out, s.value(), s.line(), s.column());
        } else if (c == '$' || isIdentifierStart(c)) {
            int kl = line;
            int kc = column;
            int start = pos;
            if (peek() == '$') {
                advance();
                while (!isEof() && isIdentifierPart(peek())) {
                    advance();
                }
            } else {
                while (!isEof() && isIdentifierPart(peek())) {
                    advance();
                }
            }
            String key = input.substring(start, pos);
            classifyKey(out, key, kl, kc);
        } else {
            throw new LexException(t("Invalid BSON object key", "Clave de objeto BSON inválida"), line, column);
        }
    }

    private void classifyKey(List<NoSqlToken> out, String key, int line0, int col0) {
        if (key.startsWith("$")) {
            out.add(new NoSqlToken(NoSqlTokenType.OPERATOR, key, line0, col0));
        } else {
            out.add(new NoSqlToken(NoSqlTokenType.FIELD_NAME, key, line0, col0));
        }
    }

    /** Returns true if {@code operatorKey} is one of the catalogued MongoDB {@code $} operators. */
    public static boolean isCataloguedOperator(String operatorKey) {
        return operatorKey != null && ALL_CATALOGUED_OPERATORS.contains(operatorKey);
    }

    private void readArrayTokens(List<NoSqlToken> out) {
        int l = line;
        int c = column;
        expectChar('[', null);
        out.add(tok(NoSqlTokenType.BRACKET_OPEN, "[", l, c));
        skipWsAndComments();
        if (peek() == ']') {
            int l2 = line;
            int c2 = column;
            advance();
            out.add(tok(NoSqlTokenType.BRACKET_CLOSE, "]", l2, c2));
            return;
        }
        while (true) {
            readValueTokens(out);
            skipWsAndComments();
            if (peek() == ']') {
                break;
            }
            if (peek() != ',') {
                throw new LexException(
                        t("Expected ',' or ']' in BSON array", "Se esperaba ',' o ']' en arreglo BSON"),
                        line,
                        column);
            }
            out.add(tok(NoSqlTokenType.COMMA, ","));
            advance();
            skipWsAndComments();
        }
        int le = line;
        int ce = column;
        advance();
        out.add(tok(NoSqlTokenType.BRACKET_CLOSE, "]", le, ce));
    }

    private NoSqlToken readStringToken() {
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
        advance(); // closing quote
        return new NoSqlToken(NoSqlTokenType.STRING, sb.toString(), l, c);
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

    private NoSqlToken readNumberToken() {
        int l = line;
        int c = column;
        int start = pos;
        if (peek() == '-') {
            advance();
        }
        while (!isEof() && Character.isDigit(peek())) {
            advance();
        }
        if (!isEof() && peek() == '.') {
            advance();
            while (!isEof() && Character.isDigit(peek())) {
                advance();
            }
        }
        if (!isEof() && (peek() == 'e' || peek() == 'E')) {
            advance();
            if (!isEof() && (peek() == '+' || peek() == '-')) {
                advance();
            }
            while (!isEof() && Character.isDigit(peek())) {
                advance();
            }
        }
        String num = input.substring(start, pos);
        if (num.equals("-") || num.isEmpty()) {
            throw new LexException(t("Invalid numeric literal", "Literal numérico inválido"), l, c);
        }
        return new NoSqlToken(NoSqlTokenType.NUMBER, num, l, c);
    }

    private String readIdentifier(String enWhat, String esWhat) {
        if (!isIdentifierStart(peek())) {
            throw new LexException(t("Expected " + enWhat, "Se esperaba " + esWhat), line, column);
        }
        int start = pos;
        while (!isEof() && isIdentifierPart(peek())) {
            advance();
        }
        return input.substring(start, pos);
    }

    private void expectWord(String word, String err) {
        int start = pos;
        for (int i = 0; i < word.length(); i++) {
            if (isEof() || Character.toLowerCase(peek()) != word.charAt(i)) {
                throw new LexException(
                        err != null ? err : t("Expected '" + word + "'", "Se esperaba '" + word + "'"),
                        line,
                        column);
            }
            advance();
        }
        if (!isEof() && isIdentifierPart(peek())) {
            pos = start;
            throw new LexException(
                    err != null ? err : t("Expected '" + word + "'", "Se esperaba '" + word + "'"),
                    line,
                    column);
        }
    }

    private void expectChar(char ch, String err) {
        skipWsAndComments();
        if (isEof() || peek() != ch) {
            throw new LexException(
                    err != null ? err : t("Expected '" + ch + "'", "Se esperaba '" + ch + "'"),
                    line,
                    column);
        }
        advance();
    }

    private static boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_' || ch == '$';
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
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

    private NoSqlToken tok(NoSqlTokenType type, String value) {
        return new NoSqlToken(type, value, line, column);
    }

    private NoSqlToken tok(NoSqlTokenType type, String value, int line0, int col0) {
        return new NoSqlToken(type, value, line0, col0);
    }

    /** Lexer-only failure; converted to {@link SemanticError} by {@link MongoDbAnalyzer}. */
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
