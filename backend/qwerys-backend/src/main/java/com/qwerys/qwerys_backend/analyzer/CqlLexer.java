package com.qwerys.qwerys_backend.analyzer;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Lexer for Apache Cassandra CQL (common DML/DDL surface and advanced constructions).
 *
 * <p>Recognizes comments ({@code --}, {@code /* ... *\/}), single-quoted strings with {@code ''}
 * escapes, double-quoted identifiers, numeric literals, collection/map delimiters, compound
 * operators {@code +=} / {@code -=}, and the keyword set expected by {@link CqlAnalyzer}.
 */
public final class CqlLexer {

    private static final Set<String> KEYWORDS = buildKeywords();

    private final String input;
    private int pos;
    private int line;
    private int column;

    public CqlLexer(String input) {
        this.input = input != null ? input : "";
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    /**
     * Tokenizes the full input (optional trailing {@code ;}; whitespace allowed).
     *
     * @throws LexException on unterminated string or orphan block comment
     */
    public List<CqlToken> tokenize() {
        List<CqlToken> out = new ArrayList<>(64);
        skipWsAndComments();
        while (!isEof()) {
            char c = peek();
            int tokLine = line;
            int tokCol = column;
            switch (c) {
                case ',' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.COMMA, ",", tokLine, tokCol));
                }
                case ';' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.SEMICOLON, ";", tokLine, tokCol));
                }
                case '(' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.LEFT_PAREN, "(", tokLine, tokCol));
                }
                case ')' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.RIGHT_PAREN, ")", tokLine, tokCol));
                }
                case '[' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.LEFT_BRACKET, "[", tokLine, tokCol));
                }
                case ']' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.RIGHT_BRACKET, "]", tokLine, tokCol));
                }
                case '{' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.LEFT_BRACE, "{", tokLine, tokCol));
                }
                case '}' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.RIGHT_BRACE, "}", tokLine, tokCol));
                }
                case '*' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.ASTERISK, "*", tokLine, tokCol));
                }
                case '.' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.DOT, ".", tokLine, tokCol));
                }
                case '<' -> {
                    advance();
                    if (peek() == '=') {
                        advance();
                        out.add(new CqlToken(CqlTokenType.OPERATOR, "<=", tokLine, tokCol));
                    } else if (peek() == '>') {
                        advance();
                        out.add(new CqlToken(CqlTokenType.OPERATOR, "<>", tokLine, tokCol));
                    } else {
                        out.add(new CqlToken(CqlTokenType.LESS_THAN, "<", tokLine, tokCol));
                    }
                }
                case '>' -> {
                    advance();
                    if (peek() == '=') {
                        advance();
                        out.add(new CqlToken(CqlTokenType.OPERATOR, ">=", tokLine, tokCol));
                    } else {
                        out.add(new CqlToken(CqlTokenType.GREATER_THAN, ">", tokLine, tokCol));
                    }
                }
                case '=' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.OPERATOR, "=", tokLine, tokCol));
                }
                case '!' -> {
                    advance();
                    if (peek() == '=') {
                        advance();
                        out.add(new CqlToken(CqlTokenType.OPERATOR, "!=", tokLine, tokCol));
                    } else {
                        out.add(new CqlToken(CqlTokenType.UNKNOWN, "!", tokLine, tokCol));
                    }
                }
                case '?' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.IDENTIFIER, "?", tokLine, tokCol));
                }
                case '+' -> {
                    advance();
                    if (peek() == '=') {
                        advance();
                        out.add(new CqlToken(CqlTokenType.OPERATOR, "+=", tokLine, tokCol));
                    } else {
                        out.add(new CqlToken(CqlTokenType.OPERATOR, "+", tokLine, tokCol));
                    }
                }
                case '-' -> {
                    advance();
                    if (peek() == '=') {
                        advance();
                        out.add(new CqlToken(CqlTokenType.OPERATOR, "-=", tokLine, tokCol));
                    } else {
                        out.add(new CqlToken(CqlTokenType.OPERATOR, "-", tokLine, tokCol));
                    }
                }
                case '/' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.OPERATOR, "/", tokLine, tokCol));
                }
                case '%' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.OPERATOR, "%", tokLine, tokCol));
                }
                case '&' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.OPERATOR, "&", tokLine, tokCol));
                }
                case '|' -> {
                    advance();
                    out.add(new CqlToken(CqlTokenType.OPERATOR, "|", tokLine, tokCol));
                }
                case '\'' -> {
                    String s = readStringLiteral();
                    out.add(new CqlToken(CqlTokenType.STRING, s, tokLine, tokCol));
                }
                case '"' -> {
                    String id = readDoubleQuotedIdentifier();
                    out.add(new CqlToken(CqlTokenType.IDENTIFIER, id, tokLine, tokCol));
                }
                default -> {
                    if (Character.isDigit(c)) {
                        String num = readNumber();
                        out.add(new CqlToken(CqlTokenType.NUMBER, num, tokLine, tokCol));
                    } else if (isIdentStart(c)) {
                        String raw = readIdentifier();
                        String upper = raw.toUpperCase(Locale.ROOT);
                        if ("TRUE".equals(upper) || "FALSE".equals(upper)) {
                            out.add(new CqlToken(CqlTokenType.BOOLEAN, raw, tokLine, tokCol));
                        } else if (KEYWORDS.contains(upper)) {
                            out.add(new CqlToken(CqlTokenType.KEYWORD, raw, tokLine, tokCol));
                        } else if (looksLikeUuidLexeme(raw)) {
                            out.add(new CqlToken(CqlTokenType.UUID_LITERAL, raw, tokLine, tokCol));
                        } else {
                            out.add(new CqlToken(CqlTokenType.IDENTIFIER, raw, tokLine, tokCol));
                        }
                    } else {
                        advance();
                        out.add(new CqlToken(CqlTokenType.UNKNOWN, String.valueOf(c), tokLine, tokCol));
                    }
                }
            }
            skipWsAndComments();
        }
        out.add(new CqlToken(CqlTokenType.EOF, "", line, column));
        return out;
    }

    private boolean looksLikeUuidLexeme(String raw) {
        if (raw.length() != 36) {
            return false;
        }
        String u = raw.toLowerCase(Locale.ROOT);
        if (u.charAt(8) != '-' || u.charAt(13) != '-' || u.charAt(18) != '-' || u.charAt(23) != '-') {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                continue;
            }
            char ch = u.charAt(i);
            if ((ch < '0' || ch > '9') && (ch < 'a' || ch > 'f')) {
                return false;
            }
        }
        return true;
    }

    private String readStringLiteral() {
        int startLine = line;
        int startCol = column;
        advance(); // opening '
        StringBuilder sb = new StringBuilder();
        sb.append('\'');
        while (!isEof()) {
            char c = peek();
            if (c == '\'') {
                advance();
                if (peek() == '\'') {
                    advance();
                    sb.append("''");
                } else {
                    sb.append('\'');
                    return sb.toString();
                }
            } else {
                advance();
                sb.append(c);
            }
        }
        throw new LexException("Unterminated string literal", startLine, startCol);
    }

    private String readDoubleQuotedIdentifier() {
        int startLine = line;
        int startCol = column;
        advance(); // opening "
        StringBuilder sb = new StringBuilder();
        while (!isEof()) {
            char c = peek();
            if (c == '"') {
                advance();
                if (peek() == '"') {
                    advance();
                    sb.append("\"\"");
                } else {
                    return sb.toString();
                }
            } else {
                advance();
                sb.append(c);
            }
        }
        throw new LexException("Unterminated quoted identifier", startLine, startCol);
    }

    private String readNumber() {
        StringBuilder sb = new StringBuilder();
        if (peek() == '0' && pos + 1 < input.length()) {
            char n = input.charAt(pos + 1);
            if (n == 'x' || n == 'X') {
                sb.append(advanceChar());
                sb.append(advanceChar());
                while (!isEof() && isHexDigit(peek())) {
                    sb.append(advanceChar());
                }
                return sb.toString();
            }
        }
        while (!isEof() && Character.isDigit(peek())) {
            sb.append(advanceChar());
        }
        if (!isEof() && peek() == '.') {
            sb.append(advanceChar());
            while (!isEof() && Character.isDigit(peek())) {
                sb.append(advanceChar());
            }
        }
        if (!isEof() && (peek() == 'e' || peek() == 'E')) {
            sb.append(advanceChar());
            if (!isEof() && (peek() == '+' || peek() == '-')) {
                sb.append(advanceChar());
            }
            while (!isEof() && Character.isDigit(peek())) {
                sb.append(advanceChar());
            }
        }
        return sb.toString();
    }

    private static boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private String readIdentifier() {
        StringBuilder sb = new StringBuilder();
        sb.append(advanceChar());
        while (!isEof() && isIdentPart(peek())) {
            sb.append(advanceChar());
        }
        return sb.toString();
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void skipWsAndComments() {
        while (!isEof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance();
                continue;
            }
            if (c == '-' && pos + 1 < input.length() && input.charAt(pos + 1) == '-') {
                skipLineComment();
                continue;
            }
            if (c == '/' && pos + 1 < input.length() && input.charAt(pos + 1) == '*') {
                skipBlockComment();
                continue;
            }
            break;
        }
    }

    private void skipLineComment() {
        while (!isEof()) {
            char c = advanceChar();
            if (c == '\n') {
                break;
            }
        }
    }

    private void skipBlockComment() {
        int startLine = line;
        int startCol = column;
        advance();
        advance(); // /*
        while (!isEof()) {
            if (peek() == '*' && pos + 1 < input.length() && input.charAt(pos + 1) == '/') {
                advance();
                advance();
                return;
            }
            advance();
        }
        throw new LexException("Unterminated block comment", startLine, startCol);
    }

    private boolean isEof() {
        return pos >= input.length();
    }

    private char peek() {
        return isEof() ? '\0' : input.charAt(pos);
    }

    private void advance() {
        if (!isEof()) {
            char c = input.charAt(pos++);
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
    }

    private char advanceChar() {
        char c = peek();
        advance();
        return c;
    }

    /**
     * Keyword set (compared case-insensitively). {@code JOIN} is intentionally omitted so {@code join}
     * is tokenized as an {@link CqlTokenType#IDENTIFIER} and caught by {@link CqlAnalyzer}.
     */
    private static Set<String> buildKeywords() {
        String[] words = {
                "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
                "DROP", "ALTER", "TABLE", "KEYSPACE", "INDEX", "MATERIALIZED", "VIEW", "TYPE", "FUNCTION",
                "AGGREGATE", "PRIMARY", "KEY", "PARTITION", "CLUSTERING", "ORDER", "BY", "WITH", "USING",
                "TTL", "TIMESTAMP", "ALLOW", "FILTERING", "BATCH", "APPLY", "LOGGED", "UNLOGGED", "IF",
                "EXISTS", "NOT", "COUNTER", "FROZEN", "LIST", "MAP", "SET", "TUPLE", "TEXT", "BLOB", "BOOLEAN",
                "TINYINT", "SMALLINT", "INT", "BIGINT", "VARINT", "FLOAT", "DOUBLE", "DECIMAL", "UUID",
                "TIMEUUID", "INET", "DATE", "TIME", "DURATION", "ASC", "DESC",
                // BATCH / control
                "BEGIN",
                // Functions & time
                "WRITETIME", "MAXWRITETIME", "CUSTOM",
                // DCL / auth surface
                "USER", "ROLE", "PERMISSION", "GRANT", "REVOKE", "PASSWORD", "SUPERUSER", "LOGIN", "NOSUPERUSER",
                // UDA / UDF
                "SFUNC", "STYPE", "FINALFUNC", "INITCOND", "OR", "REPLACE", "LANGUAGE", "RETURNS", "CALLED",
                "INPUT", "DETERMINISTIC", "JAVA", "JAVASCRIPT", "NULL",
                // Triggers & clauses
                "TRIGGER", "ON", "IN", "TO", "ADD", "RENAME",
                // Types & misc DDL
                "ASCII", "VARCHAR", "STATIC", "KEYS", "ENTRIES", "FULL", "TOKEN", "DISTINCT",
                "CAST", "AS", "JSON",
                // DCL / LIST / DROP
                "DROP", "ALL", "OF", "ROLES", "USERS", "PERMISSIONS", "LOGOUT", "AUTHORIZE", "MODIFY",
                "EXECUTE", "DESCRIBE", "DATA"
        };
        return new HashSet<>(Arrays.asList(words));
    }

    /** Lexer failure; callers may translate to {@link SemanticError} or {@link AnalysisError}. */
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

        @Override
        public String getMessage() {
            return super.getMessage() + " (line " + line + ", column " + column + ")";
        }

        /** Base lexer message without the trailing {@code (line, column)} suffix (for structured APIs). */
        public String detailMessage() {
            return super.getMessage();
        }
    }
}
