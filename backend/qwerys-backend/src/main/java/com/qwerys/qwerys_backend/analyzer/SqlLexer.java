package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SqlLexer {

    // -------------------------------------------------------------------------
    // Base keyword set — standard ANSI SQL (unchanged from original)
    // -------------------------------------------------------------------------

    private static final Set<String> BASE_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE",
            "CREATE", "DROP", "ALTER", "JOIN", "INNER", "LEFT", "RIGHT", "OUTER",
            "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET",
            "AS", "DISTINCT", "ALL", "UNION", "EXISTS", "IN",
            "BETWEEN", "LIKE", "IS", "NULL", "NOT", "AND", "OR",
            "TRUE", "FALSE", "COUNT", "SUM", "AVG", "MAX", "MIN",
            "INTO", "VALUES", "SET", "TABLE", "INDEX", "PRIMARY", "KEY",
            "FOREIGN", "REFERENCES", "CONSTRAINT", "UNIQUE", "DEFAULT", "AUTO_INCREMENT",
            "VIEW", "FUNCTION", "PROCEDURE", "TRIGGER", "SEQUENCE", "DATABASE", "SCHEMA",
            "IF", "CASE", "WHILE", "LOOP", "COLUMN", "MODIFY", "RENAME", "ADD", "REPLACE", "TEMPORARY", "TEMP",
            "CASCADE", "RESTRICT", "NO", "ACTION", "RETURNS", "RETURN", "BEGIN", "END",
            "DECLARE", "EACH", "ROW", "STATEMENT", "BEFORE", "AFTER", "INSTEAD", "OF",
            "FOR", "CALL", "EXECUTE", "EXEC", "GRANT", "REVOKE", "COMMIT", "ROLLBACK", "SAVEPOINT",
            // Procedural (shared) — EXCEPTION/OTHERS/TRY/CATCH/RAISE for multi-dialect procedural blocks (14H+)
            "THEN", "ELSE", "WHEN", "ELSIF", "ELSEIF", "DO", "EXIT",
            "EXCEPTION", "OTHERS", "HANDLER", "CONTINUE",
            "TRY", "CATCH", "RAISE", "SIGNAL", "RESIGNAL", "SQLSTATE",
            "SQLWARNING", "SQLEXCEPTION",
            "CURSOR", "OPEN", "FETCH", "CLOSE",
            "TRANSACTION", "START", "WITH", "RECURSIVE", "LATERAL", "OVER", "PARTITION",
            "ROWS", "RANGE", "UNBOUNDED", "PRECEDING", "FOLLOWING", "CURRENT", "WINDOW",
            // DCL / TCL
            "TO", "LEVEL", "ISOLATION", "READ", "WRITE", "ONLY", "WORK",
            "UNCOMMITTED", "COMMITTED", "REPEATABLE", "SERIALIZABLE", "DEFERRABLE",
            "PRIVILEGES", "CHAIN"
    );

    // -------------------------------------------------------------------------
    // Dialect-specific keyword extensions
    // -------------------------------------------------------------------------

    private static final Set<String> MYSQL_KEYWORDS = Set.of(
            "REPLACE", "SHOW", "DESCRIBE", "USE", "FULLTEXT",
            "DUPLICATE", "STRAIGHT_JOIN", "SQL_CALC_FOUND_ROWS",
            // mysql / mariadb client directive (Day 14L — not server SQL)
            "DELIMITER",
            // SQL/PSM control flow — must lex as KEYWORD so {@code LEAVE}/labeled loops parse correctly
            "LEAVE", "ITERATE", "REPEAT", "UNTIL"
    );

    private static final Set<String> POSTGRESQL_KEYWORDS = Set.of(
            "RETURNING", "ILIKE", "COPY", "VACUUM", "ANALYZE",
            "TABLESPACE", "INHERITS", "LATERAL", "EXCLUDE", "FILTER",
            "WITHIN", "SIMILAR",
            // PL/pgSQL & routine attributes
            "IMMUTABLE", "STABLE", "VOLATILE", "SECURITY", "DEFINER", "INVOKER",
            "LANGUAGE", "SETOF", "GET", "DIAGNOSTICS", "NOTIFY", "LISTEN",
            "BOOLEAN", "TEXT", "VARCHAR", "RECORD", "TRIGGER",
            "STRICT", "PARALLEL", "COST", "SUPPORT",
            "PERFORM", "QUERY", "WARNING"
    );

    private static final Set<String> SQLITE_KEYWORDS = Set.of(
            "STRICT", "GLOB", "PRAGMA", "ATTACH", "DETACH", "WITHOUT", "ROWID"
    );

    private static final Set<String> SQLSERVER_KEYWORDS = Set.of(
            "TOP", "OUTPUT", "MERGE", "PIVOT", "UNPIVOT",
            "RAISERROR", "PRINT", "NOLOCK", "READPAST", "UPDLOCK", "ROWLOCK",
            "WHEN", "THEN", "MATCHED", "USING", "INSERTED", "DELETED",
            "THROW", "GOTO", "DEALLOCATE", "WAITFOR", "NEXT", "EXEC", "EXECUTE", "NOCOUNT"
    );

    private static final Set<String> ORACLE_KEYWORDS = Set.of(
            "ROWNUM", "ROWID", "PRIOR", "DUAL", "FLASHBACK", "PURGE",
            "MERGE", "FORALL", "BULK", "CONNECT", "START", "NOCYCLE",
            "WHEN", "THEN", "MATCHED", "USING", "PIVOT", "UNPIVOT", "SIBLINGS",
            // PL/SQL program units (14I+)
            "PACKAGE", "BODY", "AUTHID", "DEFINER", "PRAGMA", "AUTONOMOUS", "TRANSACTION",
            "TYPE", "ROWTYPE", "VARCHAR2", "NUMBER", "NOCOPY", "PIPELINED"
    );

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final String query;
    private final SqlDialect dialect;
    private final Set<String> effectiveKeywords;
    private int pos;
    private int line;
    private int column;

    // -------------------------------------------------------------------------
    // Constructors — backward-compatible
    // -------------------------------------------------------------------------

    /** Original constructor — uses GENERIC dialect so existing tests are unaffected. */
    public SqlLexer(String query) {
        this(query, SqlDialect.GENERIC);
    }

    /** Dialect-aware constructor. */
    public SqlLexer(String query, SqlDialect dialect) {
        this.query   = query;
        this.dialect = dialect;
        this.pos     = 0;
        this.line    = 1;
        this.column  = 1;
        this.effectiveKeywords = buildKeywords(dialect);
    }

    // -------------------------------------------------------------------------
    // Keyword set builder
    // -------------------------------------------------------------------------

    private static Set<String> buildKeywords(SqlDialect dialect) {
        Set<String> set = new HashSet<>(BASE_KEYWORDS);
        switch (dialect) {
            case MYSQL      -> set.addAll(MYSQL_KEYWORDS);
            case POSTGRESQL -> set.addAll(POSTGRESQL_KEYWORDS);
            case SQLITE     -> set.addAll(SQLITE_KEYWORDS);
            case SQLSERVER  -> set.addAll(SQLSERVER_KEYWORDS);
            case ORACLE     -> set.addAll(ORACLE_KEYWORDS);
            default         -> { /* GENERIC — no additions */ }
        }
        return Collections.unmodifiableSet(set);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (pos < query.length()) {
            char c = current();

            if (c == '\n') {
                advance();
                line++;
                column = 1;
                continue;
            }

            if (Character.isWhitespace(c)) {
                advance();
                continue;
            }

            if (dialect == SqlDialect.SQLSERVER && c == '@') {
                tokens.add(readSqlServerAtIdentifier());
                continue;
            }

            // T-SQL Unicode string literal: N'text' — must not lex `N` as an identifier.
            if (dialect == SqlDialect.SQLSERVER
                    && (c == 'N' || c == 'n')
                    && pos + 1 < query.length()
                    && query.charAt(pos + 1) == '\'') {
                int sl = line;
                int sc = column;
                advance(); // N
                Token quoted = readString('\'');
                tokens.add(new Token(TokenType.STRING, "N" + quoted.value(), sl, sc));
                continue;
            }

            if (Character.isLetter(c) || c == '_') {
                tokens.add(readIdentifierOrKeyword());
                continue;
            }

            if (Character.isDigit(c)) {
                tokens.add(readNumber());
                continue;
            }

            if (c == '\'' || c == '"') {
                tokens.add(readString(c));
                continue;
            }

            if (dialect == SqlDialect.POSTGRESQL && c == '$') {
                Token dollar = tryReadDollarQuotedString();
                if (dollar != null) {
                    tokens.add(dollar);
                    continue;
                }
            }

            // Line comment: -- … end of line
            if (c == '-' && pos + 1 < query.length() && query.charAt(pos + 1) == '-') {
                while (pos < query.length() && query.charAt(pos) != '\n' && query.charAt(pos) != '\r') {
                    advance();
                }
                continue;
            }

            // MySQL / MariaDB line comment: # … end of line (not used in PostgreSQL — # is bitwise XOR there)
            if (dialect == SqlDialect.MYSQL && c == '#') {
                while (pos < query.length() && query.charAt(pos) != '\n' && query.charAt(pos) != '\r') {
                    advance();
                }
                continue;
            }

            // Block comment: /* … */, or MySQL /*![version] … */ (inner SQL is tokenized)
            if (c == '/' && pos + 1 < query.length() && query.charAt(pos + 1) == '*') {
                if (dialect == SqlDialect.MYSQL
                        && pos + 2 < query.length()
                        && query.charAt(pos + 2) == '!') {
                    consumeMysqlExecutableComment(tokens);
                } else {
                    skipBlockComment();
                }
                continue;
            }

            Token opToken = tryReadOperator();
            if (opToken != null) {
                tokens.add(opToken);
                continue;
            }

            Token punctToken = tryReadPunctuation();
            if (punctToken != null) {
                tokens.add(punctToken);
                continue;
            }

            tokens.add(new Token(TokenType.UNKNOWN, String.valueOf(c), line, column));
            advance();
        }

        tokens.add(new Token(TokenType.EOF, "", line, column));
        return tokens;
    }

    private void skipBlockComment() {
        advance(); // /
        advance(); // *
        while (pos + 1 < query.length()) {
            if (query.charAt(pos) == '*' && query.charAt(pos + 1) == '/') {
                advance();
                advance();
                break;
            }
            if (current() == '\n') {
                line++;
                column = 1;
            }
            advance();
        }
    }

    /**
     * MySQL {@code slash-star-bang} conditional blocks — merges inner SQL into the token stream.
     */
    private void consumeMysqlExecutableComment(List<Token> tokens) {
        int slashPos = pos;
        int lineAtSlash = line;
        int colAtSlash = column;

        advance(); // /
        advance(); // *
        advance(); // !
        while (pos < query.length() && Character.isDigit(current())) {
            advance();
        }
        int innerStart = pos;
        int endStar = MysqlExecutableCommentSupport.indexOfExecutableCommentPayloadEnd(query, innerStart);
        if (endStar < 0) {
            pos = slashPos;
            line = lineAtSlash;
            column = colAtSlash;
            skipBlockComment();
            return;
        }

        int lineAtInnerStart = line;
        int colAtInnerStart = column;

        String inner = query.substring(innerStart, endStar);
        int afterClose = endStar + 2;

        applyPositionAfter(slashPos, afterClose, lineAtSlash, colAtSlash);

        List<Token> innerTokens = new SqlLexer(inner, dialect).tokenize();
        for (Token t : innerTokens) {
            if (t.type() == TokenType.EOF) {
                continue;
            }
            tokens.add(shiftToken(t, lineAtInnerStart, colAtInnerStart));
        }
    }

    private void applyPositionAfter(int fromInclusive, int toExclusive, int lineAtFrom, int colAtFrom) {
        line = lineAtFrom;
        column = colAtFrom;
        int i = fromInclusive;
        while (i < toExclusive) {
            char ch = query.charAt(i);
            if (ch == '\r') {
                line++;
                column = 1;
                if (i + 1 < toExclusive && query.charAt(i + 1) == '\n') {
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (ch == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            i++;
        }
        pos = toExclusive;
    }

    private static Token shiftToken(Token t, int baseLine, int baseCol) {
        int nl = baseLine + (t.line() - 1);
        int nc = t.line() == 1 ? baseCol + t.column() - 1 : t.column();
        return new Token(t.type(), t.value(), nl, nc);
    }

    // -------------------------------------------------------------------------
    // Readers
    // -------------------------------------------------------------------------

    private Token readIdentifierOrKeyword() {
        int startLine = line;
        int startCol  = column;
        StringBuilder sb = new StringBuilder();

        while (pos < query.length() && (Character.isLetterOrDigit(current()) || current() == '_')) {
            sb.append(current());
            advance();
        }

        String text  = sb.toString();
        String upper = text.toUpperCase();

        if (effectiveKeywords.contains(upper)) {
            TokenType type = resolveKeywordType(upper);
            return new Token(type, text, startLine, startCol);
        }

        return new Token(TokenType.IDENTIFIER, text, startLine, startCol);
    }

    /**
     * {@code @param} local or {@code @@SYSTEM} system variable (SQL Server).
     */
    private Token readSqlServerAtIdentifier() {
        int startLine = line;
        int startCol  = column;
        advance(); // '@'
        StringBuilder sb = new StringBuilder("@");
        if (pos < query.length() && current() == '@') {
            sb.append(current());
            advance();
            while (pos < query.length()
                    && (Character.isLetterOrDigit(current()) || current() == '_')) {
                sb.append(current());
                advance();
            }
        } else {
            while (pos < query.length()
                    && (Character.isLetterOrDigit(current()) || current() == '_')) {
                sb.append(current());
                advance();
            }
        }
        return new Token(TokenType.IDENTIFIER, sb.toString(), startLine, startCol);
    }

    private Token readNumber() {
        int startLine = line;
        int startCol  = column;
        StringBuilder sb = new StringBuilder();
        boolean hasDot = false;

        while (pos < query.length() && (Character.isDigit(current()) || (current() == '.' && !hasDot))) {
            if (current() == '.') hasDot = true;
            sb.append(current());
            advance();
        }

        return new Token(TokenType.NUMBER, sb.toString(), startLine, startCol);
    }

    private Token readString(char delimiter) {
        int startLine = line;
        int startCol  = column;
        StringBuilder sb = new StringBuilder();
        sb.append(delimiter);
        advance();

        while (pos < query.length()) {
            char c = current();
            sb.append(c);
            advance();
            if (c == delimiter) break;
        }

        return new Token(TokenType.STRING, sb.toString(), startLine, startCol);
    }

    /**
     * PostgreSQL dollar-quoted string: {@code $$…$$} or {@code $tag$…$tag$}.
     * The entire literal (including delimiters) is one {@link TokenType#STRING}; no SQL is tokenized inside.
     */
    private Token tryReadDollarQuotedString() {
        if (pos >= query.length() || current() != '$') {
            return null;
        }
        int startLine = line;
        int startCol = column;
        int openerStart = pos;
        advance(); // first $
        if (pos >= query.length()) {
            pos = openerStart;
            line = startLine;
            column = startCol;
            return null;
        }
        // Tag: optional identifier between $ ... $
        while (pos < query.length()) {
            char ch = current();
            if (ch == '$') {
                break;
            }
            if (!Character.isLetterOrDigit(ch) && ch != '_') {
                pos = openerStart;
                line = startLine;
                column = startCol;
                return null;
            }
            advance();
        }
        if (pos >= query.length() || current() != '$') {
            pos = openerStart;
            line = startLine;
            column = startCol;
            return null;
        }
        advance(); // closing $ of opener
        String tag = query.substring(openerStart + 1, pos - 1);
        String closing = "$" + tag + "$";
        StringBuilder sb = new StringBuilder();
        sb.append(query, openerStart, pos);
        while (pos < query.length()) {
            if (query.startsWith(closing, pos)) {
                sb.append(closing);
                for (int i = 0; i < closing.length(); i++) {
                    advance();
                }
                return new Token(TokenType.STRING, sb.toString(), startLine, startCol);
            }
            sb.append(current());
            advance();
        }
        pos = openerStart;
        line = startLine;
        column = startCol;
        return null;
    }

    private Token tryReadOperator() {
        int startLine = line;
        int startCol  = column;
        char c    = current();
        char next = peek();

        switch (c) {
            case '!' -> {
                if (next == '=') { advance(); advance(); return new Token(TokenType.NOT_EQUALS, "!=", startLine, startCol); }
            }
            case '<' -> {
                if (next == '=') { advance(); advance(); return new Token(TokenType.LESS_EQUAL, "<=", startLine, startCol); }
                advance(); return new Token(TokenType.LESS_THAN, "<", startLine, startCol);
            }
            case '>' -> {
                if (next == '=') { advance(); advance(); return new Token(TokenType.GREATER_EQUAL, ">=", startLine, startCol); }
                advance(); return new Token(TokenType.GREATER_THAN, ">", startLine, startCol);
            }
            case ':' -> {
                if (next == '=') {
                    advance();
                    advance();
                    return new Token(TokenType.OPERATOR, ":=", startLine, startCol);
                }
                advance();
                return new Token(TokenType.COLON, ":", startLine, startCol);
            }
            case '=' -> { advance(); return new Token(TokenType.EQUALS, "=", startLine, startCol); }
            case '+' -> { advance(); return new Token(TokenType.OPERATOR, "+", startLine, startCol); }
            case '-' -> { advance(); return new Token(TokenType.OPERATOR, "-", startLine, startCol); }
            case '/' -> { advance(); return new Token(TokenType.OPERATOR, "/", startLine, startCol); }
        }
        return null;
    }

    private Token tryReadPunctuation() {
        int startLine = line;
        int startCol  = column;
        char c = current();

        return switch (c) {
            case ',' -> { advance(); yield new Token(TokenType.COMMA,       ",", startLine, startCol); }
            case ';' -> { advance(); yield new Token(TokenType.SEMICOLON,   ";", startLine, startCol); }
            case '(' -> { advance(); yield new Token(TokenType.LEFT_PAREN,  "(", startLine, startCol); }
            case ')' -> { advance(); yield new Token(TokenType.RIGHT_PAREN, ")", startLine, startCol); }
            case '*' -> { advance(); yield new Token(TokenType.ASTERISK,    "*", startLine, startCol); }
            case '.' -> { advance(); yield new Token(TokenType.DOT,         ".", startLine, startCol); }
            case '%' -> { advance(); yield new Token(TokenType.OPERATOR,     "%", startLine, startCol); }
            default  -> null;
        };
    }

    // -------------------------------------------------------------------------
    // Keyword type resolution
    // -------------------------------------------------------------------------

    private TokenType resolveKeywordType(String upper) {
        return switch (upper) {
            case "AND" -> TokenType.AND;
            case "OR"  -> TokenType.OR;
            case "NOT" -> TokenType.NOT;
            default    -> TokenType.KEYWORD;
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private char current() {
        return query.charAt(pos);
    }

    private char peek() {
        return (pos + 1 < query.length()) ? query.charAt(pos + 1) : '\0';
    }

    private void advance() {
        pos++;
        column++;
    }
}
