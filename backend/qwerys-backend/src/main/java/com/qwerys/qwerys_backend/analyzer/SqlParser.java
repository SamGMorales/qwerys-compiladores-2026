package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.analyzer.procedural.MySqlPsmParser;
import com.qwerys.qwerys_backend.analyzer.procedural.PlPgSqlParser;
import com.qwerys.qwerys_backend.analyzer.procedural.PlSqlParser;
import com.qwerys.qwerys_backend.analyzer.procedural.ProceduralParser;
import com.qwerys.qwerys_backend.analyzer.procedural.TSqlParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Recursive-descent SQL parser with dialect support.
 *
 * <p>Grammar entry point: {@link #parse()} detects the statement type and delegates to
 * the appropriate parse* method. Dialect-specific extensions are added transparently
 * without altering the generic SQL grammar.
 *
 * <p>Backward-compatible constructors:
 * <ul>
 *   <li>{@code SqlParser(List<Token> tokens)} — uses {@link SqlDialect#GENERIC} (original API)</li>
 *   <li>{@code SqlParser(List<Token> tokens, SqlDialect dialect)} — dialect-aware</li>
 * </ul>
 */
public class SqlParser {

    // =========================================================================
    // Public exception
    // =========================================================================

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    // =========================================================================
    // State
    // =========================================================================

    private final List<Token> tokens;
    private final SqlDialect dialect;
    private int pos;
    private final List<String> syntaxErrors;

    private static final Set<String> AGGREGATE_FUNCTIONS =
            Set.of("COUNT", "SUM", "AVG", "MAX", "MIN");

    // =========================================================================
    // Constructors — backward-compatible
    // =========================================================================

    /** Original constructor — uses GENERIC dialect so existing tests are unaffected. */
    public SqlParser(List<Token> tokens) {
        this(tokens, SqlDialect.GENERIC);
    }

    /** Dialect-aware constructor. */
    public SqlParser(List<Token> tokens, SqlDialect dialect) {
        this.tokens = tokens.stream()
                .filter(t -> t.type() != TokenType.WHITESPACE)
                .toList();
        this.dialect      = dialect;
        this.pos          = 0;
        this.syntaxErrors = new ArrayList<>();
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Parses the token stream and returns the root AST node.
     * Handles standard SQL plus dialect-specific top-level statements.
     */
    public AstNode parse() {
        Token first = peek();
        if (first.type() == TokenType.EOF) {
            throw new ParseException(
                    "Empty query at line " + first.line() + ", column " + first.column());
        }

        if (first.type() != TokenType.KEYWORD) {
            throw new ParseException(String.format(
                    "Expected a SQL statement keyword but found '%s' at line %d, column %d",
                    first.value(), first.line(), first.column()));
        }

        AstNode result = switch (first.value().toUpperCase()) {
            // ---- Standard SQL ------------------------------------------------
            case "SELECT"   -> parseSelectStatement();
            case "WITH"     -> parseWithSelectStatement();
            case "INSERT"   -> parseInsertStatement();
            case "UPDATE"   -> parseUpdateStatement();
            case "DELETE"   -> parseDeleteStatement();
            case "CREATE"   -> parseCreateDispatch();
            case "ALTER"    -> parseAlterStatement();
            case "DROP"     -> parseDropStatement();
            // ---- MySQL -------------------------------------------------------
            case "REPLACE"  -> parseReplaceStatement();
            case "SHOW"     -> parseShowStatement();
            case "DESCRIBE" -> parseDescribeStatement();
            case "USE"      -> parseUseStatement();
            case "DELIMITER" -> parseClientDelimiterStatement();
            // ---- PostgreSQL --------------------------------------------------
            case "COPY"     -> parseCopyStatement();
            case "VACUUM"   -> parseVacuumStatement();
            case "ANALYZE"  -> parseAnalyzeStatement();
            case "DO"       -> parseDoStatementDispatched();
            // ---- SQLite ------------------------------------------------------
            case "PRAGMA"   -> parsePragmaStatement();
            case "ATTACH"   -> parseAttachStatement();
            case "DETACH"   -> parseDetachStatement();
            // ---- SQL Server --------------------------------------------------
            case "MERGE"    -> parseMergeStatement();
            case "PRINT"    -> parsePrintStatement();
            case "RAISERROR"-> parseRaiserrorStatement();
            case "THROW", "GOTO", "DEALLOCATE", "WAITFOR" -> parseTsqlTopLevelStatement();
            case "EXEC", "EXECUTE" -> parseExecStatement();
            // ---- Oracle ------------------------------------------------------
            case "FLASHBACK"-> parseFlashbackQuery();
            case "PURGE"    -> parsePurgeStatement();
            // ---- DCL / TCL (generic) -------------------------------------
            case "GRANT"     -> parseGrantStatement();
            case "REVOKE"    -> parseRevokeStatement();
            case "DECLARE"   -> parseDeclareProceduralUnit();
            case "BEGIN"     -> parseBeginStatement();
            case "OPEN", "FETCH", "CLOSE" -> parseProceduralCursorStandalone();
            case "IF", "WHILE", "LOOP", "FOR", "CASE" -> parseProceduralStandaloneStatement();
            case "COMMIT"    -> parseCommitStatement();
            case "ROLLBACK"  -> parseRollbackStatement();
            case "SAVEPOINT" -> parseSavepointStatement();
            case "START"     -> parseStartTransaction();
            case "SET"       -> parseSetStatement();
            default -> throw new ParseException(String.format(
                    "Unknown statement type '%s' at line %d, column %d",
                    first.value(), first.line(), first.column()));
        };

        if (peek().type() == TokenType.SEMICOLON) advance();

        return result;
    }

    // =========================================================================
    // SELECT
    //
    // Grammar (GENERIC):
    //   SELECT [DISTINCT] column_list
    //   FROM   table_expression
    //   [WHERE  condition]
    //   [GROUP BY column_list]
    //   [HAVING condition]
    //   [ORDER BY order_list]
    //   [LIMIT  n [OFFSET m]]
    //
    // Dialect extensions:
    //   SQL Server : SELECT TOP n / TOP (n) [PERCENT] ...
    //   Oracle     : ... [START WITH cond] [CONNECT BY [NOCYCLE] cond]
    //   PostgreSQL : adds RETURNING via INSERT/UPDATE/DELETE (not SELECT itself)
    // =========================================================================

    private AstNode parseSelectStatement() {
        AstNode stmt = new AstNode("SELECT_STATEMENT");
        consume(TokenType.KEYWORD, "SELECT");

        // SQL Server: SELECT TOP n ...
        if (dialect == SqlDialect.SQLSERVER && matchKeyword("TOP")) {
            stmt.addChild(parseTopClause());
        }

        if (matchKeyword("DISTINCT")) {
            advance();
            stmt.addChild(new AstNode("DISTINCT"));
        }

        stmt.addChild(parseColumnList());

        consume(TokenType.KEYWORD, "FROM");
        stmt.addChild(parseTableExpression());

        // Oracle: START WITH before WHERE is valid
        if (dialect == SqlDialect.ORACLE && matchKeyword("START")) {
            stmt.addChild(parseStartWithClause());
        }

        if (matchKeyword("WHERE")) {
            stmt.addChild(parseWhereClause());
        }
        if (matchKeyword("GROUP")) {
            stmt.addChild(parseGroupBy());
        }
        if (matchKeyword("HAVING")) {
            stmt.addChild(parseHaving());
        }

        // Oracle: CONNECT BY / START WITH (may appear after WHERE too)
        if (dialect == SqlDialect.ORACLE) {
            if (matchKeyword("START")) {
                stmt.addChild(parseStartWithClause());
            }
            if (matchKeyword("CONNECT")) {
                stmt.addChild(parseConnectByClause());
            }
        }

        if (matchKeyword("ORDER")) {
            stmt.addChild(parseOrderBy());
        }
        if (matchKeyword("LIMIT")) {
            stmt.addChild(parseLimit());
        }

        return stmt;
    }

    /**
     * {@code WITH [RECURSIVE] name AS (SELECT ...) [, ...] SELECT ...} — CTE list followed by the main
     * {@link #parseSelectStatement()} query. Used at top level and inside {@code IN (WITH ...)}.
     */
    private AstNode parseWithSelectStatement() {
        AstNode root = new AstNode("WITH_SELECT_STATEMENT");
        consume(TokenType.KEYWORD, "WITH");

        if (matchKeyword("RECURSIVE")) {
            advance();
            root.addChild(new AstNode("RECURSIVE"));
        }

        AstNode withClause = new AstNode("WITH_CLAUSE");
        while (true) {
            Token nameTok = consume(TokenType.IDENTIFIER, null);
            consume(TokenType.KEYWORD, "AS");
            consume(TokenType.LEFT_PAREN, null);

            AstNode sub = new AstNode("SUBQUERY");
            if (!matchKeyword("SELECT")) {
                Token bad = peek();
                throw new ParseException(String.format(
                        "Expected SELECT to start CTE subquery but found '%s' (%s) at line %d, column %d",
                        bad.value(), bad.type(), bad.line(), bad.column()));
            }
            sub.addChild(parseSelectStatement());
            consume(TokenType.RIGHT_PAREN, null);

            AstNode cte = new AstNode("CTE_DEFINITION", nameTok.value());
            cte.addChild(sub);
            withClause.addChild(cte);

            if (peek().type() == TokenType.COMMA) {
                advance();
                continue;
            }
            break;
        }
        root.addChild(withClause);

        root.addChild(parseWithMainStatement());
        return root;
    }

    /** Statement that may follow a {@code WITH} clause (PostgreSQL DML, not only SELECT). */
    private AstNode parseWithMainStatement() {
        if (matchKeyword("SELECT")) {
            return parseSelectStatement();
        }
        if (matchKeyword("INSERT")) {
            return parseInsertStatement();
        }
        if (matchKeyword("UPDATE")) {
            return parseUpdateStatement();
        }
        if (matchKeyword("DELETE")) {
            return parseDeleteStatement();
        }
        Token bad = peek();
        throw new ParseException(String.format(
                "Expected SELECT, INSERT, UPDATE, or DELETE after WITH clause but found '%s' (%s) at line %d, column %d",
                bad.value(), bad.type(), bad.line(), bad.column()));
    }

    /**
     * Consumes {@code (} tokens that only wrap a {@code SELECT}/{@code WITH} subquery (e.g.
     * {@code IN ((SELECT ...)))}), not parenthesized value/tuple lists.
     */
    private int consumeSubqueryLeadingParenWraps() {
        int n = 0;
        while (peek().type() == TokenType.LEFT_PAREN) {
            int j = pos + 1;
            while (j < tokens.size() && tokens.get(j).type() == TokenType.LEFT_PAREN) {
                j++;
            }
            if (j >= tokens.size()) {
                break;
            }
            Token t = tokens.get(j);
            if (t.type() != TokenType.KEYWORD) {
                break;
            }
            String kw = t.value();
            if (!kw.equalsIgnoreCase("SELECT") && !kw.equalsIgnoreCase("WITH")) {
                break;
            }
            advance();
            n++;
        }
        return n;
    }

    // =========================================================================
    // SQL Server — TOP clause
    // =========================================================================

    /**
     * TOP n | TOP (n) [PERCENT]
     */
    private AstNode parseTopClause() {
        consume(TokenType.KEYWORD, "TOP");

        boolean parenthesised = peek().type() == TokenType.LEFT_PAREN;
        if (parenthesised) advance();

        Token n = consume(TokenType.NUMBER, null);

        if (parenthesised) consume(TokenType.RIGHT_PAREN, null);

        boolean percent = matchKeywordOrIdent("PERCENT");
        if (percent) advance();

        AstNode top = new AstNode(percent ? "TOP_PERCENT" : "TOP", n.value());
        return top;
    }

    // =========================================================================
    // Oracle — CONNECT BY / START WITH
    // =========================================================================

    /** START WITH condition */
    private AstNode parseStartWithClause() {
        consume(TokenType.KEYWORD, "START");
        consume(TokenType.KEYWORD, "WITH");
        AstNode startWith = new AstNode("START_WITH");
        startWith.addChild(parseCondition());
        return startWith;
    }

    /** CONNECT BY [NOCYCLE] condition */
    private AstNode parseConnectByClause() {
        consume(TokenType.KEYWORD, "CONNECT");
        consume(TokenType.KEYWORD, "BY");
        AstNode connectBy = new AstNode("CONNECT_BY");

        if (matchKeyword("NOCYCLE")) {
            advance();
            connectBy.addChild(new AstNode("NOCYCLE"));
        }

        connectBy.addChild(parseCondition());
        return connectBy;
    }

    // =========================================================================
    // Column list   (SELECT part)
    // =========================================================================

    private AstNode parseColumnList() {
        AstNode list = new AstNode("COLUMN_LIST");

        if (peek().type() == TokenType.ASTERISK) {
            advance();
            list.addChild(new AstNode("COLUMN_REF", "*"));
            return list;
        }

        list.addChild(parseColumnExpr());
        while (peek().type() == TokenType.COMMA) {
            advance();
            list.addChild(parseColumnExpr());
        }
        return list;
    }

    private AstNode parseColumnExpr() {
        Token t = peek();

        if (t.type() == TokenType.KEYWORD
                && AGGREGATE_FUNCTIONS.contains(t.value().toUpperCase())) {
            return parseFunctionCall();
        }

        if (t.type() == TokenType.IDENTIFIER) {
            String name = advance().value();

            if (peek().type() == TokenType.DOT) {
                advance();
                String col = consume(TokenType.IDENTIFIER, null).value();
                return withOptionalAlias(new AstNode("COLUMN_REF", name + "." + col));
            }

            if (peek().type() == TokenType.LEFT_PAREN) {
                AstNode fn = new AstNode("FUNCTION_CALL", name);
                advance();
                if (peek().type() != TokenType.RIGHT_PAREN) {
                    fn.addChild(parseFunctionArgs());
                }
                consume(TokenType.RIGHT_PAREN, null);
                return withOptionalAlias(fn);
            }

            return withOptionalAlias(new AstNode("COLUMN_REF", name));
        }

        if (t.type() == TokenType.NUMBER || t.type() == TokenType.STRING) {
            return new AstNode("LITERAL", advance().value());
        }

        throw new ParseException(String.format(
                "Expected column expression but found '%s' at line %d, column %d",
                t.value(), t.line(), t.column()));
    }

    private AstNode parseFunctionCall() {
        Token nameToken = advance();
        AstNode fn = new AstNode("FUNCTION_CALL", nameToken.value().toUpperCase());
        consume(TokenType.LEFT_PAREN, null);

        if (peek().type() == TokenType.ASTERISK) {
            advance();
            fn.addChild(new AstNode("ASTERISK", "*"));
        } else if (matchKeyword("DISTINCT")) {
            advance();
            fn.addChild(new AstNode("DISTINCT"));
            fn.addChild(parseFunctionArgs());
        } else if (peek().type() != TokenType.RIGHT_PAREN) {
            fn.addChild(parseFunctionArgs());
        }

        consume(TokenType.RIGHT_PAREN, null);
        return withOptionalAlias(fn);
    }

    private AstNode parseFunctionArgs() {
        AstNode args = new AstNode("FUNCTION_ARGS");
        args.addChild(parseColumnExpr());
        while (peek().type() == TokenType.COMMA) {
            advance();
            args.addChild(parseColumnExpr());
        }
        return args;
    }

    private AstNode withOptionalAlias(AstNode node) {
        if (matchKeyword("AS")) {
            advance();
            Token alias = peek();
            if (alias.type() != TokenType.IDENTIFIER && alias.type() != TokenType.KEYWORD) {
                throw new ParseException(String.format(
                        "Expected alias name after AS at line %d, column %d",
                        alias.line(), alias.column()));
            }
            node.addChild(new AstNode("ALIAS", advance().value()));
        }
        return node;
    }

    // =========================================================================
    // Table reference / JOIN
    // =========================================================================

    private AstNode parseTableExpression() {
        AstNode tableRef = parseTableRef();

        while (matchKeyword("JOIN")   || matchKeyword("INNER") ||
               matchKeyword("LEFT")   || matchKeyword("RIGHT") ||
               matchKeyword("OUTER")  || matchKeywordOrIdent("FULL") ||
               matchKeyword("CROSS")  ||
               (dialect == SqlDialect.POSTGRESQL && matchKeyword("LATERAL"))) {

            // PostgreSQL: bare LATERAL (cross-lateral join)
            if (dialect == SqlDialect.POSTGRESQL && matchKeyword("LATERAL")) {
                advance();
                AstNode lateral = parseLateralJoin();
                AstNode joinExpr = new AstNode("JOIN_EXPRESSION");
                joinExpr.addChild(tableRef);
                joinExpr.addChild(lateral);
                tableRef = joinExpr;
                continue;
            }

            AstNode join = parseJoin();
            AstNode joinExpr = new AstNode("JOIN_EXPRESSION");
            joinExpr.addChild(tableRef);
            joinExpr.addChild(join);
            tableRef = joinExpr;
        }

        return tableRef;
    }

    private AstNode parseTableRef() {
        Token t = peek();
        if (t.type() != TokenType.IDENTIFIER && t.type() != TokenType.KEYWORD) {
            throw new ParseException(String.format(
                    "Expected table name at line %d, column %d", t.line(), t.column()));
        }

        String name = advance().value();

        if (peek().type() == TokenType.DOT) {
            advance();
            name = name + "." + consume(TokenType.IDENTIFIER, null).value();
        }

        AstNode ref = new AstNode("TABLE_REF", name);

        if (matchKeyword("AS")) {
            advance();
            ref.addChild(new AstNode("ALIAS", consume(TokenType.IDENTIFIER, null).value()));
        } else if (peek().type() == TokenType.IDENTIFIER) {
            ref.addChild(new AstNode("ALIAS", advance().value()));
        }

        return ref;
    }

    private AstNode parseJoin() {
        StringBuilder joinType = new StringBuilder();

        while (matchKeyword("LEFT")   || matchKeyword("RIGHT") ||
               matchKeyword("INNER")  || matchKeyword("OUTER") ||
               matchKeywordOrIdent("FULL") || matchKeyword("CROSS")) {
            if (!joinType.isEmpty()) joinType.append(" ");
            joinType.append(advance().value().toUpperCase());
        }

        // PostgreSQL: LATERAL after join qualifier
        boolean lateral = (dialect == SqlDialect.POSTGRESQL && matchKeyword("LATERAL"));
        if (lateral) advance();

        consume(TokenType.KEYWORD, "JOIN");
        if (!joinType.isEmpty()) joinType.append(" ");
        joinType.append("JOIN");

        AstNode join = new AstNode(lateral ? "LATERAL_JOIN" : "JOIN", joinType.toString());
        join.addChild(lateral ? parseLateralJoin() : parseTableRef());
        consume(TokenType.KEYWORD, "ON");
        join.addChild(parseCondition());
        return join;
    }

    // =========================================================================
    // PostgreSQL — LATERAL join
    // =========================================================================

    /**
     * LATERAL (subquery | function_call) [AS alias]
     */
    private AstNode parseLateralJoin() {
        AstNode lateral = new AstNode("LATERAL_REF");

        if (peek().type() == TokenType.LEFT_PAREN) {
            // LATERAL (subquery)
            advance();
            lateral.addChild(new AstNode("SUBQUERY"));
            int depth = 1;
            while (depth > 0 && peek().type() != TokenType.EOF) {
                if (peek().type() == TokenType.LEFT_PAREN)  depth++;
                else if (peek().type() == TokenType.RIGHT_PAREN) depth--;
                if (depth > 0) advance();
            }
            consume(TokenType.RIGHT_PAREN, null);
        } else {
            // LATERAL function()
            lateral.addChild(parseTableRef());
        }

        if (matchKeyword("AS")) {
            advance();
            lateral.addChild(new AstNode("ALIAS", consume(TokenType.IDENTIFIER, null).value()));
        } else if (peek().type() == TokenType.IDENTIFIER) {
            lateral.addChild(new AstNode("ALIAS", advance().value()));
        }

        return lateral;
    }

    // =========================================================================
    // WHERE clause
    // =========================================================================

    private AstNode parseWhereClause() {
        consume(TokenType.KEYWORD, "WHERE");
        AstNode where = new AstNode("WHERE_CLAUSE");
        where.addChild(parseCondition());
        return where;
    }

    // =========================================================================
    // Condition / boolean expression
    // =========================================================================

    private AstNode parseCondition() { return parseOrExpression(); }

    private AstNode parseOrExpression() {
        AstNode left = parseAndExpression();
        while (peek().type() == TokenType.OR) {
            advance();
            AstNode right = parseAndExpression();
            AstNode or = new AstNode("OR_EXPR");
            or.addChild(left);
            or.addChild(right);
            left = or;
        }
        return left;
    }

    private AstNode parseAndExpression() {
        AstNode left = parseNotExpression();
        while (peek().type() == TokenType.AND) {
            advance();
            AstNode right = parseNotExpression();
            AstNode and = new AstNode("AND_EXPR");
            and.addChild(left);
            and.addChild(right);
            left = and;
        }
        return left;
    }

    private AstNode parseNotExpression() {
        if (peek().type() == TokenType.NOT) {
            advance();
            AstNode not = new AstNode("NOT_EXPR");
            not.addChild(parsePredicate());
            return not;
        }
        return parsePredicate();
    }

    private AstNode parsePredicate() {
        if (peek().type() == TokenType.LEFT_PAREN) {
            advance();
            AstNode inner = parseCondition();
            consume(TokenType.RIGHT_PAREN, null);
            return inner;
        }

        if (matchKeyword("EXISTS")) {
            advance();
            AstNode exists = new AstNode("EXISTS_EXPR");
            consume(TokenType.LEFT_PAREN, null);
            exists.addChild(new AstNode("SUBQUERY"));
            consume(TokenType.RIGHT_PAREN, null);
            return exists;
        }

        AstNode left = parseValueExpr();

        if (matchKeyword("IS")) {
            advance();
            boolean negated = peek().type() == TokenType.NOT;
            if (negated) advance();
            consume(TokenType.KEYWORD, "NULL");
            AstNode node = new AstNode(negated ? "IS_NOT_NULL" : "IS_NULL");
            node.addChild(left);
            return node;
        }

        boolean notPrefix = peek().type() == TokenType.NOT
                && peekAhead(1).type() == TokenType.KEYWORD
                && peekAhead(1).value().equalsIgnoreCase("IN");
        if (notPrefix) {
            advance(); advance();
            AstNode in = new AstNode("NOT_IN_EXPR");
            in.addChild(left);
            in.addChild(parseInList());
            return in;
        }
        if (matchKeyword("IN")) {
            advance();
            AstNode in = new AstNode("IN_EXPR");
            in.addChild(left);
            in.addChild(parseInList());
            return in;
        }

        boolean notBetween = peek().type() == TokenType.NOT
                && peekAhead(1).type() == TokenType.KEYWORD
                && peekAhead(1).value().equalsIgnoreCase("BETWEEN");
        if (notBetween) {
            advance(); advance();
            return buildBetween("NOT_BETWEEN_EXPR", left);
        }
        if (matchKeyword("BETWEEN")) {
            advance();
            return buildBetween("BETWEEN_EXPR", left);
        }

        boolean notLike = peek().type() == TokenType.NOT
                && peekAhead(1).type() == TokenType.KEYWORD
                && peekAhead(1).value().equalsIgnoreCase("LIKE");
        if (notLike) {
            advance(); advance();
            AstNode like = new AstNode("NOT_LIKE_EXPR");
            like.addChild(left);
            like.addChild(parseValueExpr());
            return like;
        }
        if (matchKeyword("LIKE")) {
            advance();
            AstNode like = new AstNode("LIKE_EXPR");
            like.addChild(left);
            like.addChild(parseValueExpr());
            return like;
        }

        // PostgreSQL: ILIKE
        if (dialect == SqlDialect.POSTGRESQL && matchKeyword("ILIKE")) {
            advance();
            AstNode ilike = new AstNode("ILIKE_EXPR");
            ilike.addChild(left);
            ilike.addChild(parseValueExpr());
            return ilike;
        }

        if (isComparisonOp(peek())) {
            Token op = advance();
            AstNode cmp = new AstNode("COMPARISON", op.value());
            cmp.addChild(left);
            cmp.addChild(parseValueExpr());
            return cmp;
        }

        return left;
    }

    private AstNode buildBetween(String nodeType, AstNode left) {
        AstNode between = new AstNode(nodeType);
        between.addChild(left);
        between.addChild(parseValueExpr());
        consume(TokenType.AND, null);
        between.addChild(parseValueExpr());
        return between;
    }

    /**
     * Parses {@code (...)} after {@code IN} / {@code NOT IN}: either a comma-separated value list
     * or a single {@code SELECT}/{@code WITH ... SELECT} subquery (optionally wrapped in extra
     * parentheses, e.g. {@code IN ((SELECT ...))}). Scalar subqueries without {@code FROM} are not
     * supported because {@link #parseSelectStatement()} requires a {@code FROM} clause.
     */
    private AstNode parseInList() {
        consume(TokenType.LEFT_PAREN, null);
        AstNode list = new AstNode("IN_LIST");

        int extraCloseParens = consumeSubqueryLeadingParenWraps();

        if (matchKeyword("WITH")) {
            AstNode subquery = new AstNode("SUBQUERY");
            subquery.addChild(parseWithSelectStatement());
            list.addChild(subquery);
        } else if (matchKeyword("SELECT")) {
            AstNode subquery = new AstNode("SUBQUERY");
            subquery.addChild(parseSelectStatement());
            list.addChild(subquery);
        } else {
            if (extraCloseParens > 0) {
                Token bad = peek();
                throw new ParseException(String.format(
                        "Extra '(' before IN list does not start a SELECT/WITH subquery (found '%s' at line %d, column %d)",
                        bad.value(), bad.line(), bad.column()));
            }
            list.addChild(parseValueExpr());
            while (peek().type() == TokenType.COMMA) {
                advance();
                list.addChild(parseValueExpr());
            }
        }

        while (extraCloseParens > 0) {
            consume(TokenType.RIGHT_PAREN, null);
            extraCloseParens--;
        }
        consume(TokenType.RIGHT_PAREN, null);
        return list;
    }

    private AstNode parseValueExpr() {
        Token t = peek();

        if (t.type() == TokenType.KEYWORD
                && AGGREGATE_FUNCTIONS.contains(t.value().toUpperCase())) {
            return parseFunctionCall();
        }

        if (t.type() == TokenType.IDENTIFIER) {
            String name = advance().value();

            if (peek().type() == TokenType.DOT) {
                advance();
                String col = consume(TokenType.IDENTIFIER, null).value();
                return new AstNode("COLUMN_REF", name + "." + col);
            }

            if (peek().type() == TokenType.LEFT_PAREN) {
                AstNode fn = new AstNode("FUNCTION_CALL", name);
                advance();
                if (peek().type() != TokenType.RIGHT_PAREN) {
                    fn.addChild(parseFunctionArgs());
                }
                consume(TokenType.RIGHT_PAREN, null);
                return fn;
            }

            return new AstNode("COLUMN_REF", name);
        }

        // Oracle: ROWNUM/ROWID can appear in WHERE as keyword pseudo-columns
        if ((dialect == SqlDialect.ORACLE)
                && t.type() == TokenType.KEYWORD
                && (t.value().equalsIgnoreCase("ROWNUM") || t.value().equalsIgnoreCase("ROWID"))) {
            return new AstNode("COLUMN_REF", advance().value().toUpperCase());
        }

        if (t.type() == TokenType.NUMBER) return new AstNode("LITERAL", advance().value());
        if (t.type() == TokenType.STRING) return new AstNode("LITERAL", advance().value());

        if (t.type() == TokenType.KEYWORD) {
            String up = t.value().toUpperCase();
            if (up.equals("TRUE") || up.equals("FALSE") || up.equals("NULL")) {
                return new AstNode("LITERAL", advance().value().toUpperCase());
            }
        }

        throw new ParseException(String.format(
                "Expected value expression but found '%s' (%s) at line %d, column %d",
                t.value(), t.type(), t.line(), t.column()));
    }

    // =========================================================================
    // GROUP BY / HAVING / ORDER BY / LIMIT
    // =========================================================================

    private AstNode parseGroupBy() {
        consume(TokenType.KEYWORD, "GROUP");
        consume(TokenType.KEYWORD, "BY");
        AstNode groupBy = new AstNode("GROUP_BY");
        groupBy.addChild(parseColumnExpr());
        while (peek().type() == TokenType.COMMA) {
            advance();
            groupBy.addChild(parseColumnExpr());
        }
        return groupBy;
    }

    private AstNode parseHaving() {
        consume(TokenType.KEYWORD, "HAVING");
        AstNode having = new AstNode("HAVING_CLAUSE");
        having.addChild(parseCondition());
        return having;
    }

    private AstNode parseOrderBy() {
        consume(TokenType.KEYWORD, "ORDER");
        consume(TokenType.KEYWORD, "BY");
        AstNode orderBy = new AstNode("ORDER_BY");
        orderBy.addChild(parseOrderExpr());
        while (peek().type() == TokenType.COMMA) {
            advance();
            orderBy.addChild(parseOrderExpr());
        }
        return orderBy;
    }

    private AstNode parseOrderExpr() {
        AstNode expr = parseColumnExpr();
        Token dir = peek();
        String dirVal = dir.value().toUpperCase();

        if ((dir.type() == TokenType.IDENTIFIER || dir.type() == TokenType.KEYWORD)
                && (dirVal.equals("ASC") || dirVal.equals("DESC"))) {
            advance();
            AstNode order = new AstNode("ORDER_EXPR", dirVal);
            order.addChild(expr);
            return order;
        }

        AstNode order = new AstNode("ORDER_EXPR", "ASC");
        order.addChild(expr);
        return order;
    }

    private AstNode parseLimit() {
        consume(TokenType.KEYWORD, "LIMIT");
        Token n = consume(TokenType.NUMBER, null);
        AstNode limit = new AstNode("LIMIT", n.value());

        if (matchKeyword("OFFSET")) {
            advance();
            limit.addChild(new AstNode("OFFSET", consume(TokenType.NUMBER, null).value()));
        }

        return limit;
    }

    // =========================================================================
    // INSERT
    // =========================================================================

    private AstNode parseInsertStatement() {
        AstNode stmt = new AstNode("INSERT_STATEMENT");
        consume(TokenType.KEYWORD, "INSERT");
        consume(TokenType.KEYWORD, "INTO");
        stmt.addChild(parseTableRef());

        if (peek().type() == TokenType.LEFT_PAREN) {
            advance();
            AstNode cols = new AstNode("COLUMN_LIST");
            cols.addChild(new AstNode("COLUMN_REF", consume(TokenType.IDENTIFIER, null).value()));
            while (peek().type() == TokenType.COMMA) {
                advance();
                cols.addChild(new AstNode("COLUMN_REF", consume(TokenType.IDENTIFIER, null).value()));
            }
            consume(TokenType.RIGHT_PAREN, null);
            stmt.addChild(cols);
        }

        consume(TokenType.KEYWORD, "VALUES");
        AstNode valuesNode = new AstNode("VALUES");
        valuesNode.addChild(parseValueRow());
        while (peek().type() == TokenType.COMMA) {
            advance();
            valuesNode.addChild(parseValueRow());
        }
        stmt.addChild(valuesNode);

        // MySQL: ON DUPLICATE KEY UPDATE
        if (dialect == SqlDialect.MYSQL && matchKeyword("ON")) {
            Token ahead2 = peekAhead(1);
            Token ahead3 = peekAhead(2);
            if (ahead2.value().equalsIgnoreCase("DUPLICATE")
                    && ahead3.value().equalsIgnoreCase("KEY")) {
                advance(); advance(); advance(); // ON DUPLICATE KEY
                consume(TokenType.KEYWORD, "UPDATE");
                AstNode onDupKey = new AstNode("ON_DUPLICATE_KEY_UPDATE");
                onDupKey.addChild(parseAssignment());
                while (peek().type() == TokenType.COMMA) {
                    advance();
                    onDupKey.addChild(parseAssignment());
                }
                stmt.addChild(onDupKey);
            }
        }

        // PostgreSQL: RETURNING clause
        if (dialect == SqlDialect.POSTGRESQL && matchKeyword("RETURNING")) {
            stmt.addChild(parseReturningClause());
        }

        return stmt;
    }

    private AstNode parseValueRow() {
        consume(TokenType.LEFT_PAREN, null);
        AstNode row = new AstNode("VALUE_ROW");
        row.addChild(parseValueExpr());
        while (peek().type() == TokenType.COMMA) {
            advance();
            row.addChild(parseValueExpr());
        }
        consume(TokenType.RIGHT_PAREN, null);
        return row;
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    private AstNode parseUpdateStatement() {
        AstNode stmt = new AstNode("UPDATE_STATEMENT");
        consume(TokenType.KEYWORD, "UPDATE");
        stmt.addChild(parseTableRef());
        consume(TokenType.KEYWORD, "SET");

        AstNode setClause = new AstNode("SET_CLAUSE");
        setClause.addChild(parseAssignment());
        while (peek().type() == TokenType.COMMA) {
            advance();
            setClause.addChild(parseAssignment());
        }
        stmt.addChild(setClause);

        if (matchKeyword("WHERE")) {
            stmt.addChild(parseWhereClause());
        }

        // PostgreSQL: RETURNING clause
        if (dialect == SqlDialect.POSTGRESQL && matchKeyword("RETURNING")) {
            stmt.addChild(parseReturningClause());
        }

        return stmt;
    }

    private AstNode parseAssignment() {
        Token col = consume(TokenType.IDENTIFIER, null);
        consume(TokenType.EQUALS, null);
        AstNode assign = new AstNode("ASSIGNMENT", col.value());
        assign.addChild(parseValueExpr());
        return assign;
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    private AstNode parseDeleteStatement() {
        AstNode stmt = new AstNode("DELETE_STATEMENT");
        consume(TokenType.KEYWORD, "DELETE");
        consume(TokenType.KEYWORD, "FROM");
        stmt.addChild(parseTableRef());

        if (matchKeyword("WHERE")) {
            stmt.addChild(parseWhereClause());
        }

        // PostgreSQL: RETURNING clause
        if (dialect == SqlDialect.POSTGRESQL && matchKeyword("RETURNING")) {
            stmt.addChild(parseReturningClause());
        }

        return stmt;
    }

    // =========================================================================
    // CREATE TABLE
    // =========================================================================

    private AstNode parseCreateTable() {
        AstNode stmt = new AstNode("CREATE_TABLE_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");
        consume(TokenType.KEYWORD, "TABLE");

        if (peek().type() == TokenType.IDENTIFIER
                && peek().value().equalsIgnoreCase("IF")) {
            advance();
            consume(TokenType.NOT, null);
            consume(TokenType.KEYWORD, "EXISTS");
            stmt.addChild(new AstNode("IF_NOT_EXISTS"));
        }

        stmt.addChild(parseTableRef());
        consume(TokenType.LEFT_PAREN, null);

        AstNode columns = new AstNode("COLUMN_DEFINITIONS");
        columns.addChild(parseColumnDefinitionOrConstraint());

        while (peek().type() == TokenType.COMMA) {
            advance();
            columns.addChild(parseColumnDefinitionOrConstraint());
        }

        consume(TokenType.RIGHT_PAREN, null);
        stmt.addChild(columns);

        // SQLite: WITHOUT ROWID
        if (dialect == SqlDialect.SQLITE && matchKeyword("WITHOUT")) {
            advance();
            consume(TokenType.KEYWORD, "ROWID");
            stmt.addChild(new AstNode("WITHOUT_ROWID"));
        }

        return stmt;
    }

    private AstNode parseColumnDefinitionOrConstraint() {
        Token t = peek();
        String up = t.value().toUpperCase();

        if ((t.type() == TokenType.KEYWORD && (up.equals("PRIMARY") || up.equals("FOREIGN")
                || up.equals("UNIQUE") || up.equals("INDEX") || up.equals("CONSTRAINT")))
                || (t.type() == TokenType.NOT && up.equals("NOT"))) {
            return parseTableConstraint();
        }

        return parseColumnDefinition();
    }

    private AstNode parseColumnDefinition() {
        Token name = consume(TokenType.IDENTIFIER, null);
        AstNode colDef = new AstNode("COLUMN_DEF", name.value());

        Token typeTok = peek();
        if (typeTok.type() == TokenType.KEYWORD || typeTok.type() == TokenType.IDENTIFIER) {
            String dataType = advance().value().toUpperCase();

            if (peek().type() == TokenType.LEFT_PAREN) {
                advance();
                StringBuilder tp = new StringBuilder(dataType).append("(");
                tp.append(consume(TokenType.NUMBER, null).value());
                if (peek().type() == TokenType.COMMA) {
                    advance();
                    tp.append(",").append(consume(TokenType.NUMBER, null).value());
                }
                consume(TokenType.RIGHT_PAREN, null);
                tp.append(")");
                dataType = tp.toString();
            }

            colDef.addChild(new AstNode("DATA_TYPE", dataType));
        }

        while (isInlineConstraintStart()) {
            colDef.addChild(parseInlineConstraint());
        }

        return colDef;
    }

    private boolean isInlineConstraintStart() {
        Token t = peek();
        if (t.type() == TokenType.NOT) return true;
        if (t.type() != TokenType.KEYWORD) return false;
        return switch (t.value().toUpperCase()) {
            case "NULL", "DEFAULT", "PRIMARY", "UNIQUE", "AUTO_INCREMENT", "REFERENCES" -> true;
            default -> false;
        };
    }

    private AstNode parseInlineConstraint() {
        Token t = peek();

        if (t.type() == TokenType.NOT) {
            advance();
            consume(TokenType.KEYWORD, "NULL");
            return new AstNode("CONSTRAINT", "NOT NULL");
        }

        return switch (t.value().toUpperCase()) {
            case "NULL"           -> { advance(); yield new AstNode("CONSTRAINT", "NULL"); }
            case "PRIMARY"        -> {
                advance();
                consume(TokenType.KEYWORD, "KEY");
                yield new AstNode("CONSTRAINT", "PRIMARY KEY");
            }
            case "UNIQUE"         -> { advance(); yield new AstNode("CONSTRAINT", "UNIQUE"); }
            case "AUTO_INCREMENT" -> { advance(); yield new AstNode("CONSTRAINT", "AUTO_INCREMENT"); }
            case "DEFAULT"        -> {
                advance();
                AstNode def = new AstNode("DEFAULT");
                def.addChild(parseValueExpr());
                yield def;
            }
            case "REFERENCES"     -> {
                advance();
                AstNode ref = new AstNode("REFERENCES");
                ref.addChild(parseTableRef());
                if (peek().type() == TokenType.LEFT_PAREN) {
                    ref.addChild(parseColumnRefList());
                }
                yield ref;
            }
            default -> { advance(); yield new AstNode("CONSTRAINT", t.value().toUpperCase()); }
        };
    }

    private AstNode parseTableConstraint() {
        AstNode constraint = new AstNode("TABLE_CONSTRAINT");

        if (matchKeyword("CONSTRAINT")) {
            advance();
            if (peek().type() == TokenType.IDENTIFIER) {
                constraint.addChild(new AstNode("CONSTRAINT_NAME", advance().value()));
            }
        }

        Token t = peek();
        String up = t.value().toUpperCase();

        switch (up) {
            case "PRIMARY" -> {
                advance();
                consume(TokenType.KEYWORD, "KEY");
                constraint.addChild(new AstNode("CONSTRAINT", "PRIMARY KEY"));
                constraint.addChild(parseColumnRefList());
            }
            case "FOREIGN" -> {
                advance();
                consume(TokenType.KEYWORD, "KEY");
                constraint.addChild(new AstNode("CONSTRAINT", "FOREIGN KEY"));
                constraint.addChild(parseColumnRefList());
                consume(TokenType.KEYWORD, "REFERENCES");
                constraint.addChild(parseTableRef());
                constraint.addChild(parseColumnRefList());
            }
            case "UNIQUE" -> {
                advance();
                constraint.addChild(new AstNode("CONSTRAINT", "UNIQUE"));
                constraint.addChild(parseColumnRefList());
            }
            case "INDEX" -> {
                advance();
                constraint.addChild(new AstNode("CONSTRAINT", "INDEX"));
                if (peek().type() == TokenType.IDENTIFIER) {
                    constraint.addChild(new AstNode("INDEX_NAME", advance().value()));
                }
                constraint.addChild(parseColumnRefList());
            }
            default -> throw new ParseException(String.format(
                    "Unknown table constraint '%s' at line %d, column %d",
                    t.value(), t.line(), t.column()));
        }

        return constraint;
    }

    private AstNode parseColumnRefList() {
        consume(TokenType.LEFT_PAREN, null);
        AstNode list = new AstNode("COLUMN_LIST");
        list.addChild(new AstNode("COLUMN_REF", consume(TokenType.IDENTIFIER, null).value()));
        while (peek().type() == TokenType.COMMA) {
            advance();
            list.addChild(new AstNode("COLUMN_REF", consume(TokenType.IDENTIFIER, null).value()));
        }
        consume(TokenType.RIGHT_PAREN, null);
        return list;
    }

    // =========================================================================
    // CREATE — dispatcher
    //
    // Peeks at the token after CREATE to decide which sub-parser to invoke.
    // parseCreateTable() is called unchanged for CREATE TABLE.
    // =========================================================================

    private AstNode parseCreateDispatch() {
        if (dialect == SqlDialect.ORACLE) {
            AstNode plsql = tryParseOraclePlSqlCreate();
            if (plsql != null) {
                return plsql;
            }
        }
        if (dialect == SqlDialect.SQLSERVER) {
            AstNode tsql = tryParseSqlServerCreate();
            if (tsql != null) {
                return tsql;
            }
        }
        if (dialect == SqlDialect.POSTGRESQL) {
            AstNode pg = tryParsePostgreSqlPlpgsqlCreate();
            if (pg != null) {
                return pg;
            }
        }
        if (dialect == SqlDialect.MYSQL) {
            AstNode mysqlPsm = tryParseMysqlPsmCreate();
            if (mysqlPsm != null) {
                return mysqlPsm;
            }
        }

        Token next = peekAhead(1);
        String nextUp = next.value().toUpperCase();

        return switch (nextUp) {
            case "TABLE"        -> parseCreateTable();
            case "VIEW"         -> parseCreateView();
            case "UNIQUE"       -> parseCreateIndex(true);
            case "INDEX"        -> parseCreateIndex(false);
            case "MATERIALIZED" -> parseCreateMaterializedView();
            case "SCHEMA", "DATABASE" -> parseCreateSchema();
            default             -> parseCreateTable();
        };
    }

    /**
     * Oracle PL/SQL stored objects — {@code null} if this input is not an Oracle program unit.
     */
    private AstNode tryParseOraclePlSqlCreate() {
        Token second = peekAhead(1);
        if (second == null) {
            return null;
        }
        String s2 = second.value().toUpperCase();

        if ("OR".equals(s2)) {
            Token replaceTok = peekAhead(2);
            if (replaceTok == null || !"REPLACE".equalsIgnoreCase(replaceTok.value())) {
                return null;
            }
            Token fourth = peekAhead(3);
            if (fourth == null) {
                return null;
            }
            String s4 = fourth.value().toUpperCase();
            if ("PROCEDURE".equals(s4)) {
                return parseOracleCreateProcedure(true);
            }
            if ("FUNCTION".equals(s4)) {
                return parseOracleCreateFunction(true);
            }
            if ("TRIGGER".equals(s4)) {
                return parseOracleCreateTrigger(true);
            }
            if ("PACKAGE".equals(s4)) {
                Token fifth = peekAhead(4);
                if (fifth != null && "BODY".equalsIgnoreCase(fifth.value())) {
                    return parseOracleCreatePackageBody(true);
                }
                return parseOracleCreatePackage(true);
            }
            return null;
        }

        if ("PROCEDURE".equals(s2)) {
            return parseOracleCreateProcedure(false);
        }
        if ("FUNCTION".equals(s2)) {
            return parseOracleCreateFunction(false);
        }
        if ("TRIGGER".equals(s2)) {
            return parseOracleCreateTrigger(false);
        }
        if ("PACKAGE".equals(s2)) {
            Token third = peekAhead(2);
            if (third != null && "BODY".equalsIgnoreCase(third.value())) {
                return parseOracleCreatePackageBody(false);
            }
            return parseOracleCreatePackage(false);
        }
        return null;
    }

    /**
     * T-SQL program objects: {@code CREATE [OR ALTER] PROCEDURE|FUNCTION|TRIGGER … AS …}.
     *
     * @return {@code null} if this stream is not a SQL Server create for those object types.
     */
    private AstNode tryParseSqlServerCreate() {
        if (dialect != SqlDialect.SQLSERVER) {
            return null;
        }
        Token second = peekAhead(1);
        if (second == null) {
            return null;
        }
        String s2 = second.value().toUpperCase(Locale.ROOT);
        if ("OR".equals(s2)) {
            Token third = peekAhead(2);
            if (third == null || !"ALTER".equalsIgnoreCase(third.value())) {
                return null;
            }
            Token fourth = peekAhead(3);
            if (fourth == null) {
                return null;
            }
            String s4 = fourth.value().toUpperCase(Locale.ROOT);
            if ("PROCEDURE".equals(s4) || "PROC".equals(s4)) {
                return parseSqlServerCreateRoutine(true, true);
            }
            if ("FUNCTION".equals(s4)) {
                return parseSqlServerCreateRoutine(false, true);
            }
            if ("TRIGGER".equals(s4)) {
                return parseSqlServerCreateTrigger(true);
            }
            return null;
        }
        if ("PROCEDURE".equals(s2) || "PROC".equals(s2)) {
            return parseSqlServerCreateRoutine(true, false);
        }
        if ("FUNCTION".equals(s2)) {
            return parseSqlServerCreateRoutine(false, false);
        }
        if ("TRIGGER".equals(s2)) {
            return parseSqlServerCreateTrigger(false);
        }
        return null;
    }

    private AstNode parseSqlServerCreateRoutine(boolean procedure, boolean orAlter) {
        AstNode stmt = new AstNode(
                procedure ? "CREATE_PROCEDURE_STATEMENT" : "CREATE_FUNCTION_STATEMENT",
                orAlter ? "OR_ALTER" : null);
        consume(TokenType.KEYWORD, "CREATE");
        if (orAlter) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "ALTER");
        }
        if (procedure) {
            if (matchKeyword("PROC")) {
                advance();
            } else {
                consume(TokenType.KEYWORD, "PROCEDURE");
            }
        } else {
            consume(TokenType.KEYWORD, "FUNCTION");
        }
        List<Token> headerTokens = new ArrayList<>();
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && matchKeyword("AS")) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            headerTokens.add(advance());
        }
        String sig = joinSqlServerHeaderTokens(headerTokens);
        String paramList = extractSqlServerParameterListText(headerTokens, procedure);
        if (paramList != null && !paramList.isBlank()) {
            stmt.addChild(new AstNode("PARAMETER_LIST", paramList));
        }
        stmt.addChild(new AstNode("ROUTINE_SIGNATURE", sig.trim()));
        consume(TokenType.KEYWORD, "AS");
        TSqlParser pp = new TSqlParser(tokens, dialect);
        pp.setPosition(pos);
        AstNode body = pp.parseRoutineBodyAfterAs();
        pos = pp.getPosition();
        stmt.addChild(body);
        return stmt;
    }

    private static String joinSqlServerHeaderTokens(List<Token> headerTokens) {
        StringBuilder sb = new StringBuilder();
        for (Token t : headerTokens) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t.value());
        }
        return sb.toString();
    }

    /**
     * Wraps T-SQL formal parameters so {@link RoutineParameterSupport} can parse {@code PARAMETER_LIST} uniformly.
     */
    private static String extractSqlServerParameterListText(List<Token> headerTokens, boolean procedure) {
        if (headerTokens.isEmpty()) {
            return null;
        }
        if (procedure) {
            return extractSqlServerProcedureParamList(headerTokens);
        }
        return extractSqlServerFunctionParamList(headerTokens);
    }

    private static List<Token> sliceBeforeReturnsAtDepthZero(List<Token> tokens) {
        int depth = 0;
        List<Token> out = new ArrayList<>();
        for (Token t : tokens) {
            if (depth == 0 && t.type() == TokenType.KEYWORD && "RETURNS".equalsIgnoreCase(t.value())) {
                break;
            }
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            out.add(t);
        }
        return out;
    }

    private static String extractSqlServerFunctionParamList(List<Token> headerTokens) {
        List<Token> pre = sliceBeforeReturnsAtDepthZero(headerTokens);
        int depth = 0;
        for (int i = 0; i < pre.size(); i++) {
            Token t = pre.get(i);
            if (t.type() == TokenType.LEFT_PAREN && depth == 0) {
                int d = 0;
                for (int j = i; j < pre.size(); j++) {
                    Token x = pre.get(j);
                    if (x.type() == TokenType.LEFT_PAREN) {
                        d++;
                    } else if (x.type() == TokenType.RIGHT_PAREN) {
                        d--;
                        if (d == 0) {
                            return joinTokenValues(pre, i, j + 1);
                        }
                    }
                }
                return null;
            }
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
        }
        return null;
    }

    private static String extractSqlServerProcedureParamList(List<Token> headerTokens) {
        int depth = 0;
        int start = -1;
        for (int i = 0; i < headerTokens.size(); i++) {
            Token t = headerTokens.get(i);
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            } else if (depth == 0 && t.value().startsWith("@")) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        return "(" + joinTokenValues(headerTokens, start, headerTokens.size()) + ")";
    }

    private static String joinTokenValues(List<Token> tokens, int from, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < toExclusive; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(tokens.get(i).value());
        }
        return sb.toString();
    }

    private AstNode parseSqlServerCreateTrigger(boolean orAlter) {
        AstNode stmt = new AstNode("CREATE_TRIGGER_STATEMENT", orAlter ? "OR_ALTER" : null);
        consume(TokenType.KEYWORD, "CREATE");
        if (orAlter) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "ALTER");
        }
        consume(TokenType.KEYWORD, "TRIGGER");
        int depth = 0;
        StringBuilder header = new StringBuilder();
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && matchKeyword("AS")) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (header.length() > 0) {
                header.append(' ');
            }
            header.append(advance().value());
        }
        stmt.addChild(new AstNode("TRIGGER_HEADER", header.toString().trim()));
        consume(TokenType.KEYWORD, "AS");
        TSqlParser pp = new TSqlParser(tokens, dialect);
        pp.setPosition(pos);
        AstNode body = pp.parseRoutineBodyAfterAs();
        pos = pp.getPosition();
        stmt.addChild(body);
        return stmt;
    }

    private AstNode parseDoStatementDispatched() {
        if (dialect == SqlDialect.POSTGRESQL) {
            return parsePostgreSqlDoStatement();
        }
        throw new ParseException(String.format(
                "'DO' only supports PostgreSQL PL/pgSQL anonymous blocks (line %d)",
                peek().line()));
    }

    /**
     * PostgreSQL routines/triggers backed by dollar-quoted PL/pgSQL; {@code null} if token stream differs.
     */
    private AstNode tryParsePostgreSqlPlpgsqlCreate() {
        if (dialect != SqlDialect.POSTGRESQL) {
            return null;
        }
        Token second = peekAhead(1);
        if (second == null) {
            return null;
        }
        String s2 = second.value().toUpperCase(Locale.ROOT);
        if ("OR".equals(s2)) {
            Token fourth = peekAhead(3);
            if (fourth == null) {
                return null;
            }
            String s4 = fourth.value().toUpperCase(Locale.ROOT);
            if ("FUNCTION".equals(s4)) {
                return parsePostgreSqlCreateFunction(true);
            }
            if ("PROCEDURE".equals(s4)) {
                return parsePostgreSqlCreateProcedure(true);
            }
            if ("TRIGGER".equals(s4)) {
                return parsePostgreSqlCreateTrigger(true);
            }
            return null;
        }
        if ("FUNCTION".equals(s2)) {
            return parsePostgreSqlCreateFunction(false);
        }
        if ("PROCEDURE".equals(s2)) {
            return parsePostgreSqlCreateProcedure(false);
        }
        if ("TRIGGER".equals(s2)) {
            return parsePostgreSqlCreateTrigger(false);
        }
        return null;
    }

    /** MySQL SQL/PSM stored procedures, functions, and triggers. */
    private AstNode tryParseMysqlPsmCreate() {
        if (dialect != SqlDialect.MYSQL) {
            return null;
        }
        Token second = peekAhead(1);
        if (second == null) {
            return null;
        }
        String s2 = second.value().toUpperCase(Locale.ROOT);
        if ("OR".equals(s2)) {
            Token third = peekAhead(2);
            if (third == null || !"REPLACE".equalsIgnoreCase(third.value())) {
                return null;
            }
            Token fourth = peekAhead(3);
            if (fourth == null) {
                return null;
            }
            String s4 = fourth.value().toUpperCase(Locale.ROOT);
            if ("PROCEDURE".equals(s4)) {
                return parseMysqlCreateProcedure(true);
            }
            if ("FUNCTION".equals(s4)) {
                return parseMysqlCreateFunction(true);
            }
            if ("TRIGGER".equals(s4)) {
                return parseMysqlCreateTrigger(true);
            }
            return null;
        }
        if ("PROCEDURE".equals(s2)) {
            return parseMysqlCreateProcedure(false);
        }
        if ("FUNCTION".equals(s2)) {
            return parseMysqlCreateFunction(false);
        }
        if ("TRIGGER".equals(s2)) {
            return parseMysqlCreateTrigger(false);
        }
        return null;
    }

    private AstNode parseMysqlCreateProcedure(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_PROCEDURE_STATEMENT", orReplace ? "OR_REPLACE" : null);
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "PROCEDURE");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        if (peek().type() == TokenType.LEFT_PAREN) {
            stmt.addChild(parseParenthesizedTextNode("PARAMETER_LIST"));
        }
        parseMysqlRoutineCharacteristicsUntilBegin(stmt);
        MySqlPsmParser pp = new MySqlPsmParser(tokens, dialect);
        pp.setPosition(pos);
        AstNode body = pp.parseBlockFromBegin();
        pos = pp.getPosition();
        stmt.addChild(body);
        return stmt;
    }

    private AstNode parseMysqlCreateFunction(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_FUNCTION_STATEMENT", orReplace ? "OR_REPLACE" : null);
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "FUNCTION");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        if (peek().type() == TokenType.LEFT_PAREN) {
            stmt.addChild(parseParenthesizedTextNode("PARAMETER_LIST"));
        }
        consume(TokenType.KEYWORD, "RETURNS");
        stmt.addChild(parseMysqlReturnTypeBeforeCharacteristics());
        parseMysqlRoutineCharacteristicsUntilBegin(stmt);
        MySqlPsmParser pp = new MySqlPsmParser(tokens, dialect);
        pp.setPosition(pos);
        AstNode body = pp.parseBlockFromBegin();
        pos = pp.getPosition();
        stmt.addChild(body);
        return stmt;
    }

    private AstNode parseMysqlReturnTypeBeforeCharacteristics() {
        StringBuilder rt = new StringBuilder();
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && matchKeyword("BEGIN")) {
                break;
            }
            if (depth == 0 && isMysqlRoutineCharacteristicKeyword()) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (rt.length() > 0) {
                rt.append(' ');
            }
            rt.append(advance().value());
        }
        String s = rt.toString().trim();
        if (s.isEmpty()) {
            throw new ParseException(String.format(
                    "Expected return type after RETURNS at line %d",
                    peek().line()));
        }
        return new AstNode("RETURN_TYPE", s);
    }

    private boolean isMysqlRoutineCharacteristicKeyword() {
        Token t = peek();
        if (t.type() != TokenType.KEYWORD) {
            return false;
        }
        String u = t.value().toUpperCase(Locale.ROOT);
        if ("DETERMINISTIC".equals(u) || "CONTAINS".equals(u) || "READS".equals(u)
                || "MODIFIES".equals(u) || "COMMENT".equals(u) || "LANGUAGE".equals(u)) {
            return true;
        }
        if ("SQL".equals(u)) {
            return true;
        }
        if ("NOT".equals(u)) {
            Token n = peekAhead(1);
            return n != null && n.type() == TokenType.KEYWORD
                    && "DETERMINISTIC".equalsIgnoreCase(n.value());
        }
        if ("NO".equals(u)) {
            Token n = peekAhead(1);
            return n != null && n.type() == TokenType.KEYWORD
                    && "SQL".equalsIgnoreCase(n.value());
        }
        return false;
    }

    private void parseMysqlRoutineCharacteristicsUntilBegin(AstNode stmt) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && matchKeyword("BEGIN")) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(advance().value());
        }
        String chars = sb.toString().trim();
        if (!chars.isEmpty()) {
            stmt.addChild(new AstNode("ROUTINE_CHARACTERISTICS", chars));
        }
    }

    private AstNode parseMysqlCreateTrigger(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_TRIGGER_STATEMENT", orReplace ? "OR_REPLACE" : null);
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "TRIGGER");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        StringBuilder header = new StringBuilder();
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && matchKeyword("BEGIN")) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (header.length() > 0) {
                header.append(' ');
            }
            header.append(advance().value());
        }
        stmt.addChild(new AstNode("TRIGGER_HEADER", header.toString().trim()));
        MySqlPsmParser pp = new MySqlPsmParser(tokens, dialect);
        pp.setPosition(pos);
        AstNode body = pp.parseBlockFromBegin();
        pos = pp.getPosition();
        stmt.addChild(body);
        return stmt;
    }

    private AstNode parsePostgreSqlDoStatement() {
        AstNode stmt = new AstNode("DO_STATEMENT");
        consume(TokenType.KEYWORD, "DO");
        Token lit = peek();
        if (lit.type() != TokenType.STRING) {
            throw new ParseException(String.format(
                    "Expected dollar-quoted or string body after DO at line %d",
                    lit.line()));
        }
        String literal = advance().value();
        stmt.addChild(new AstNode("BODY_LITERAL", literal));
        stmt.addChild(PlPgSqlParser.parseInnerBody(literal, dialect));
        return stmt;
    }

    private AstNode parsePostgreSqlCreateFunction(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_FUNCTION_STATEMENT", orReplace ? "OR_REPLACE" : null);
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "FUNCTION");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        if (peek().type() == TokenType.LEFT_PAREN) {
            stmt.addChild(parseParenthesizedTextNode("PARAMETER_LIST"));
        }
        StringBuilder sig = new StringBuilder();
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && matchKeyword("AS")) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (sig.length() > 0) {
                sig.append(' ');
            }
            sig.append(advance().value());
        }
        stmt.addChild(new AstNode("ROUTINE_SIGNATURE", sig.toString().trim()));
        consume(TokenType.KEYWORD, "AS");
        Token bodyTok = peek();
        if (bodyTok.type() != TokenType.STRING) {
            throw new ParseException(String.format(
                    "Expected dollar-quoted or string body after AS at line %d",
                    bodyTok.line()));
        }
        String literal = advance().value();
        stmt.addChild(new AstNode("ROUTINE_BODY_LITERAL", literal));
        stmt.addChild(PlPgSqlParser.parseInnerBody(literal, dialect));
        consumePostgreSqlTrailingRoutineClauses(stmt);
        return stmt;
    }

    private AstNode parsePostgreSqlCreateProcedure(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_PROCEDURE_STATEMENT", orReplace ? "OR_REPLACE" : null);
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "PROCEDURE");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        if (peek().type() == TokenType.LEFT_PAREN) {
            stmt.addChild(parseParenthesizedTextNode("PARAMETER_LIST"));
        }
        StringBuilder sig = new StringBuilder();
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && matchKeyword("AS")) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (sig.length() > 0) {
                sig.append(' ');
            }
            sig.append(advance().value());
        }
        stmt.addChild(new AstNode("ROUTINE_SIGNATURE", sig.toString().trim()));
        consume(TokenType.KEYWORD, "AS");
        Token bodyTok = peek();
        if (bodyTok.type() != TokenType.STRING) {
            throw new ParseException(String.format(
                    "Expected dollar-quoted or string body after AS at line %d",
                    bodyTok.line()));
        }
        String literal = advance().value();
        stmt.addChild(new AstNode("ROUTINE_BODY_LITERAL", literal));
        stmt.addChild(PlPgSqlParser.parseInnerBody(literal, dialect));
        consumePostgreSqlTrailingRoutineClauses(stmt);
        return stmt;
    }

    private void consumePostgreSqlTrailingRoutineClauses(AstNode stmt) {
        while (peek().type() != TokenType.EOF && peek().type() != TokenType.SEMICOLON) {
            stmt.addChild(new AstNode("ROUTINE_TRAILER", advance().value()));
        }
    }

    private AstNode parsePostgreSqlCreateTrigger(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_TRIGGER_STATEMENT", orReplace ? "OR_REPLACE" : null);
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "TRIGGER");
        int depth = 0;
        StringBuilder header = new StringBuilder();
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && peek().type() == TokenType.SEMICOLON) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (header.length() > 0) {
                header.append(' ');
            }
            header.append(advance().value());
        }
        stmt.addChild(new AstNode("TRIGGER_HEADER", header.toString().trim()));
        return stmt;
    }

    private AstNode parseOracleCreateProcedure(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_PROCEDURE_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "PROCEDURE");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        if (peek().type() == TokenType.LEFT_PAREN) {
            stmt.addChild(parseParenthesizedTextNode("PARAMETER_LIST"));
        }
        consumeOptionalOracleAuthId(stmt);
        if (matchKeyword("IS") || matchKeyword("AS")) {
            advance();
        } else {
            throw new ParseException(String.format(
                    "Expected IS or AS after procedure header at line %d, column %d",
                    peek().line(), peek().column()));
        }
        PlSqlParser pp = new PlSqlParser(tokens, dialect);
        pp.setPosition(pos);
        AstNode body = pp.parseRoutineBodyAfterIsAs();
        pos = pp.getPosition();
        stmt.addChild(body);
        return stmt;
    }

    private AstNode parseOracleCreateFunction(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_FUNCTION_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "FUNCTION");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        if (peek().type() == TokenType.LEFT_PAREN) {
            stmt.addChild(parseParenthesizedTextNode("PARAMETER_LIST"));
        }
        consume(TokenType.KEYWORD, "RETURN");
        stmt.addChild(new AstNode("RETURN_TYPE", parseOracleSignatureFragmentUntilAuthOrAs()));
        consumeOptionalOracleAuthId(stmt);
        if (matchKeyword("IS") || matchKeyword("AS")) {
            advance();
        } else {
            throw new ParseException(String.format(
                    "Expected IS or AS after function signature at line %d, column %d",
                    peek().line(), peek().column()));
        }
        PlSqlParser pp = new PlSqlParser(tokens, dialect);
        pp.setPosition(pos);
        AstNode body = pp.parseRoutineBodyAfterIsAs();
        pos = pp.getPosition();
        stmt.addChild(body);
        return stmt;
    }

    private AstNode parseOracleCreateTrigger(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_TRIGGER_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "TRIGGER");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        StringBuilder header = new StringBuilder();
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
            if (depth == 0 && (matchKeyword("IS") || matchKeyword("AS")
                    || matchKeyword("BEGIN") || matchKeyword("DECLARE"))) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (header.length() > 0) {
                header.append(' ');
            }
            header.append(advance().value());
        }
        stmt.addChild(new AstNode("TRIGGER_HEADER", header.toString().trim()));
        if (matchKeyword("IS") || matchKeyword("AS")) {
            advance();
        }
        // IS/AS is optional in Oracle triggers — body starts directly with BEGIN
        PlSqlParser pp = new PlSqlParser(tokens, dialect);
        pp.setPosition(pos);
        AstNode body = pp.parseRoutineBodyAfterIsAs();
        pos = pp.getPosition();
        stmt.addChild(body);
        return stmt;
    }

    private AstNode parseOracleCreatePackage(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_PACKAGE_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "PACKAGE");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        consumeOptionalOracleAuthId(stmt);
        if (matchKeyword("IS") || matchKeyword("AS")) {
            advance();
        } else {
            throw new ParseException(String.format(
                    "Expected IS or AS after package name at line %d, column %d",
                    peek().line(), peek().column()));
        }
        stmt.addChild(new AstNode("PACKAGE_SPEC_RAW", consumeRemainingInputText()));
        return stmt;
    }

    private AstNode parseOracleCreatePackageBody(boolean orReplace) {
        AstNode stmt = new AstNode("CREATE_PACKAGE_BODY_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");
        if (orReplace) {
            consumeOrKeyword();
            consume(TokenType.KEYWORD, "REPLACE");
        }
        consume(TokenType.KEYWORD, "PACKAGE");
        consume(TokenType.KEYWORD, "BODY");
        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }
        consumeOptionalOracleAuthId(stmt);
        if (matchKeyword("IS") || matchKeyword("AS")) {
            advance();
        } else {
            throw new ParseException(String.format(
                    "Expected IS or AS after package body name at line %d, column %d",
                    peek().line(), peek().column()));
        }
        if (matchKeyword("DECLARE")) {
            PlSqlParser pp = new PlSqlParser(tokens, dialect);
            pp.setPosition(pos);
            AstNode body = pp.parseBlockFromDeclare();
            pos = pp.getPosition();
            stmt.addChild(body);
        } else if (matchKeyword("BEGIN")) {
            PlSqlParser pp = new PlSqlParser(tokens, dialect);
            pp.setPosition(pos);
            AstNode body = pp.parseBlockFromBegin();
            pos = pp.getPosition();
            stmt.addChild(body);
        } else {
            stmt.addChild(new AstNode("PACKAGE_BODY_RAW", consumeRemainingInputText()));
        }
        return stmt;
    }

    private void consumeOptionalOracleAuthId(AstNode stmt) {
        if (!matchKeyword("AUTHID")) {
            return;
        }
        advance();
        AstNode auth = new AstNode("AUTHID_CLAUSE");
        Token mode = peek();
        if (mode.type() == TokenType.IDENTIFIER || mode.type() == TokenType.KEYWORD) {
            auth.addChild(new AstNode("MODE", advance().value()));
        }
        stmt.addChild(auth);
    }

    /** Reads type/signature tokens until {@code IS}, {@code AS}, or {@code AUTHID} at paren depth 0. */
    private String parseOracleSignatureFragmentUntilAuthOrAs() {
        StringBuilder sb = new StringBuilder();
        int parens = 0;
        while (peek().type() != TokenType.EOF) {
            if (parens == 0
                    && (matchKeyword("IS") || matchKeyword("AS") || matchKeyword("AUTHID"))) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                parens++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                parens--;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(advance().value());
        }
        return sb.toString().trim();
    }

    /** {@code ( ... )} as a single text node (parameters or trigger clause). */
    private AstNode parseParenthesizedTextNode(String nodeType) {
        Token open = peek();
        if (open.type() != TokenType.LEFT_PAREN) {
            return new AstNode(nodeType, "");
        }
        advance();
        StringBuilder sb = new StringBuilder();
        sb.append("(");
        int depth = 1;
        while (depth > 0 && peek().type() != TokenType.EOF) {
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                depth++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                depth--;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(advance().value());
        }
        return new AstNode(nodeType, sb.toString());
    }

    /** Best-effort capture of the rest of this statement (single-statement analysis context). */
    private String consumeRemainingInputText() {
        StringBuilder sb = new StringBuilder();
        while (peek().type() != TokenType.EOF) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(advance().value());
        }
        return sb.toString().trim();
    }

    private ProceduralParser newProceduralParser() {
        if (dialect == SqlDialect.ORACLE) {
            return new PlSqlParser(tokens, dialect);
        }
        if (dialect == SqlDialect.SQLSERVER) {
            return new TSqlParser(tokens, dialect);
        }
        if (dialect == SqlDialect.POSTGRESQL) {
            return new PlPgSqlParser(tokens, dialect);
        }
        if (dialect == SqlDialect.MYSQL) {
            return new MySqlPsmParser(tokens, dialect);
        }
        return new ProceduralParser(tokens, dialect);
    }

    // =========================================================================
    // CREATE VIEW
    //
    // CREATE VIEW name AS SELECT_STATEMENT
    // =========================================================================

    private AstNode parseCreateView() {
        AstNode stmt = new AstNode("CREATE_VIEW_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");
        consume(TokenType.KEYWORD, "VIEW");

        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("VIEW_NAME", advance().value()));
        }

        consume(TokenType.KEYWORD, "AS");
        stmt.addChild(parseSelectStatement());

        return stmt;
    }

    // =========================================================================
    // CREATE [UNIQUE] INDEX
    //
    // CREATE [UNIQUE] INDEX name ON table (col1 [, col2 ...])
    // =========================================================================

    private AstNode parseCreateIndex(boolean unique) {
        AstNode stmt = new AstNode("CREATE_INDEX_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");

        if (unique) {
            consume(TokenType.KEYWORD, "UNIQUE");
            stmt.addChild(new AstNode("UNIQUE_FLAG"));
        }

        consume(TokenType.KEYWORD, "INDEX");

        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("INDEX_NAME", advance().value()));
        }

        consume(TokenType.KEYWORD, "ON");
        stmt.addChild(parseTableRef());

        if (peek().type() == TokenType.LEFT_PAREN) {
            stmt.addChild(parseColumnRefList());
        }

        return stmt;
    }

    // =========================================================================
    // CREATE MATERIALIZED VIEW  (PostgreSQL, Cassandra)
    //
    // CREATE MATERIALIZED VIEW name AS SELECT_STATEMENT
    // =========================================================================

    private AstNode parseCreateMaterializedView() {
        AstNode stmt = new AstNode("CREATE_MATERIALIZED_VIEW_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");
        consume(TokenType.KEYWORD, "MATERIALIZED");
        consume(TokenType.KEYWORD, "VIEW");

        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("VIEW_NAME", advance().value()));
        }

        consume(TokenType.KEYWORD, "AS");
        stmt.addChild(parseSelectStatement());

        return stmt;
    }

    // =========================================================================
    // CREATE SCHEMA / CREATE DATABASE
    //
    // CREATE {SCHEMA | DATABASE} [IF NOT EXISTS] name
    // =========================================================================

    private AstNode parseCreateSchema() {
        AstNode stmt = new AstNode("CREATE_SCHEMA_STATEMENT");
        consume(TokenType.KEYWORD, "CREATE");

        Token kind = peek();
        if (kind.type() == TokenType.KEYWORD || kind.type() == TokenType.IDENTIFIER) {
            stmt.addChild(new AstNode("OBJECT_TYPE", advance().value().toUpperCase()));
        }

        if (matchKeywordOrIdent("IF")) {
            advance();
            consume(TokenType.NOT, null);
            consume(TokenType.KEYWORD, "EXISTS");
            stmt.addChild(new AstNode("IF_NOT_EXISTS"));
        }

        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("SCHEMA_NAME", advance().value()));
        }

        return stmt;
    }

    // =========================================================================
    // ALTER TABLE
    //
    // ALTER TABLE name
    //   ADD [COLUMN] col_name data_type
    //   | DROP [COLUMN] col_name
    //   | RENAME [COLUMN] old_name TO new_name
    //   | RENAME TO new_table_name
    //   | ALTER [COLUMN] col_name [SET DATA] TYPE data_type   (PostgreSQL syntax)
    //   | MODIFY [COLUMN] col_name data_type                  (MySQL syntax)
    // =========================================================================

    private AstNode parseAlterStatement() {
        AstNode stmt = new AstNode("ALTER_TABLE_STATEMENT");
        consume(TokenType.KEYWORD, "ALTER");
        consume(TokenType.KEYWORD, "TABLE");
        stmt.addChild(parseTableRef());

        Token actionToken = peek();
        String actionUp = actionToken.value().toUpperCase();

        switch (actionUp) {
            case "ADD" -> {
                advance();
                if (matchKeyword("COLUMN")) advance();
                AstNode op = new AstNode("ALTER_OPERATION", "ADD_COLUMN");
                Token colName = peek();
                if (colName.type() == TokenType.IDENTIFIER || colName.type() == TokenType.KEYWORD) {
                    op.addChild(new AstNode("COLUMN_NAME", advance().value()));
                }
                // Consume data type
                Token dt = peek();
                if (dt.type() == TokenType.KEYWORD || dt.type() == TokenType.IDENTIFIER) {
                    op.addChild(new AstNode("DATA_TYPE", advance().value().toUpperCase()));
                }
                stmt.addChild(op);
            }
            case "DROP" -> {
                advance();
                if (matchKeyword("COLUMN")) advance();
                AstNode op = new AstNode("ALTER_OPERATION", "DROP_COLUMN");
                Token colName = peek();
                if (colName.type() == TokenType.IDENTIFIER || colName.type() == TokenType.KEYWORD) {
                    op.addChild(new AstNode("COLUMN_NAME", advance().value()));
                }
                stmt.addChild(op);
            }
            case "RENAME" -> {
                advance();
                if (matchKeyword("COLUMN")) {
                    advance();
                    AstNode op = new AstNode("ALTER_OPERATION", "RENAME_COLUMN");
                    Token oldName = peek();
                    if (oldName.type() == TokenType.IDENTIFIER || oldName.type() == TokenType.KEYWORD) {
                        op.addChild(new AstNode("COLUMN_NAME", advance().value()));
                    }
                    if (matchKeyword("TO")) {
                        advance();
                        Token newName = peek();
                        if (newName.type() == TokenType.IDENTIFIER || newName.type() == TokenType.KEYWORD) {
                            op.addChild(new AstNode("NEW_NAME", advance().value()));
                        }
                    }
                    stmt.addChild(op);
                } else if (matchKeyword("TO")) {
                    advance();
                    AstNode op = new AstNode("ALTER_OPERATION", "RENAME_TABLE");
                    Token newName = peek();
                    if (newName.type() == TokenType.IDENTIFIER || newName.type() == TokenType.KEYWORD) {
                        op.addChild(new AstNode("NEW_NAME", advance().value()));
                    }
                    stmt.addChild(op);
                } else {
                    AstNode op = new AstNode("ALTER_OPERATION", "RENAME_COLUMN");
                    stmt.addChild(op);
                }
            }
            case "ALTER", "MODIFY" -> {
                advance();
                if (matchKeyword("COLUMN")) advance();
                AstNode op = new AstNode("ALTER_OPERATION", "ALTER_COLUMN");
                Token colName = peek();
                if (colName.type() == TokenType.IDENTIFIER || colName.type() == TokenType.KEYWORD) {
                    op.addChild(new AstNode("COLUMN_NAME", advance().value()));
                }
                // consume rest of column type definition (optional TYPE keyword in Postgres)
                if (matchKeyword("SET")) { advance(); }
                if (matchKeyword("DATA")) { advance(); }
                if (matchKeyword("TYPE") || matchKeywordOrIdent("TYPE")) { advance(); }
                Token dt = peek();
                if (dt.type() == TokenType.KEYWORD || dt.type() == TokenType.IDENTIFIER) {
                    op.addChild(new AstNode("DATA_TYPE", advance().value().toUpperCase()));
                }
                stmt.addChild(op);
            }
            default -> {
                // unknown ALTER sub-command — consume until semicolon/EOF
                AstNode op = new AstNode("ALTER_OPERATION", actionUp);
                advance();
                stmt.addChild(op);
            }
        }

        return stmt;
    }

    // =========================================================================
    // DROP
    //
    // DROP {TABLE | VIEW | INDEX | DATABASE | SCHEMA} [IF EXISTS] name
    // =========================================================================

    private AstNode parseDropStatement() {
        AstNode stmt = new AstNode("DROP_STATEMENT");
        consume(TokenType.KEYWORD, "DROP");

        Token objectTypeToken = peek();
        String objectType = (objectTypeToken.type() == TokenType.KEYWORD
                || objectTypeToken.type() == TokenType.IDENTIFIER)
                ? advance().value().toUpperCase()
                : "UNKNOWN";

        stmt.addChild(new AstNode("OBJECT_TYPE", objectType));

        if (matchKeywordOrIdent("IF")) {
            advance();
            consume(TokenType.KEYWORD, "EXISTS");
            stmt.addChild(new AstNode("IF_EXISTS"));
        }

        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("OBJECT_NAME", advance().value()));
        }

        return stmt;
    }

    // =========================================================================
    // PostgreSQL — RETURNING clause
    // =========================================================================

    /**
     * RETURNING col1 [, col2 ...]
     */
    private AstNode parseReturningClause() {
        consume(TokenType.KEYWORD, "RETURNING");
        AstNode returning = new AstNode("RETURNING_CLAUSE");

        if (peek().type() == TokenType.ASTERISK) {
            advance();
            returning.addChild(new AstNode("COLUMN_REF", "*"));
        } else {
            returning.addChild(parseColumnExpr());
            while (peek().type() == TokenType.COMMA) {
                advance();
                returning.addChild(parseColumnExpr());
            }
        }

        return returning;
    }

    // =========================================================================
    // MySQL — REPLACE and SHOW
    // =========================================================================

    /**
     * REPLACE [INTO] table (cols) VALUES (vals) [, ...]
     * Semantically identical to INSERT; replaces existing row on PK/UK conflict.
     */
    private AstNode parseReplaceStatement() {
        AstNode stmt = new AstNode("REPLACE_STATEMENT");
        consume(TokenType.KEYWORD, "REPLACE");

        if (matchKeyword("INTO")) advance();

        stmt.addChild(parseTableRef());

        if (peek().type() == TokenType.LEFT_PAREN) {
            advance();
            AstNode cols = new AstNode("COLUMN_LIST");
            cols.addChild(new AstNode("COLUMN_REF", consume(TokenType.IDENTIFIER, null).value()));
            while (peek().type() == TokenType.COMMA) {
                advance();
                cols.addChild(new AstNode("COLUMN_REF", consume(TokenType.IDENTIFIER, null).value()));
            }
            consume(TokenType.RIGHT_PAREN, null);
            stmt.addChild(cols);
        }

        consume(TokenType.KEYWORD, "VALUES");
        AstNode valuesNode = new AstNode("VALUES");
        valuesNode.addChild(parseValueRow());
        while (peek().type() == TokenType.COMMA) {
            advance();
            valuesNode.addChild(parseValueRow());
        }
        stmt.addChild(valuesNode);

        return stmt;
    }

    /**
     * SHOW TABLES | SHOW DATABASES | SHOW CREATE TABLE name | SHOW COLUMNS FROM name | ...
     * Consumed as a best-effort statement (complex grammar varies per sub-command).
     */
    private AstNode parseShowStatement() {
        AstNode stmt = new AstNode("SHOW_STATEMENT");
        consume(TokenType.KEYWORD, "SHOW");

        StringBuilder sb = new StringBuilder();
        while (peek().type() != TokenType.SEMICOLON
                && peek().type() != TokenType.EOF) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(advance().value());
        }
        stmt.addChild(new AstNode("SHOW_CLAUSE", sb.toString()));
        return stmt;
    }

    /** DESCRIBE table [column] */
    private AstNode parseDescribeStatement() {
        AstNode stmt = new AstNode("DESCRIBE_STATEMENT");
        consume(TokenType.KEYWORD, "DESCRIBE");
        stmt.addChild(parseTableRef());
        return stmt;
    }

    /** USE database */
    private AstNode parseUseStatement() {
        AstNode stmt = new AstNode("USE_STATEMENT");
        consume(TokenType.KEYWORD, "USE");
        Token db = peek();
        if (db.type() == TokenType.IDENTIFIER || db.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("DATABASE_REF", advance().value()));
        }
        return stmt;
    }

    // =========================================================================
    // DCL — GRANT / REVOKE
    // =========================================================================

    /**
     * GRANT privilege [, ...] ON object TO principal  [WITH GRANT OPTION]
     */
    private AstNode parseGrantStatement() {
        AstNode stmt = new AstNode("GRANT_STATEMENT");
        consume(TokenType.KEYWORD, "GRANT");
        parsePrivilegeList(stmt);
        consume(TokenType.KEYWORD, "ON");
        stmt.addChild(parseOnObjectClause());
        consume(TokenType.KEYWORD, "TO");
        stmt.addChild(parsePrincipalChild("GRANTEE"));
        skipRestOfStatement();
        return stmt;
    }

    /**
     * REVOKE privilege [, ...] ON object FROM principal [CASCADE | RESTRICT]
     */
    private AstNode parseRevokeStatement() {
        AstNode stmt = new AstNode("REVOKE_STATEMENT");
        consume(TokenType.KEYWORD, "REVOKE");
        parsePrivilegeList(stmt);
        consume(TokenType.KEYWORD, "ON");
        stmt.addChild(parseOnObjectClause());
        consume(TokenType.KEYWORD, "FROM");
        stmt.addChild(parsePrincipalChild("REVOKEE"));
        skipRestOfStatement();
        return stmt;
    }

    private void parsePrivilegeList(AstNode parent) {
        while (true) {
            if (matchKeyword("ON")) break;

            if (matchKeywordOrIdent("ALL")) {
                advance();
                if (matchKeywordOrIdent("PRIVILEGES")) advance();
                parent.addChild(new AstNode("PRIVILEGE", "ALL"));
            } else {
                Token t = peek();
                if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.KEYWORD) {
                    parent.addChild(new AstNode("PRIVILEGE",
                            advance().value().replace(' ', '_').toUpperCase()));
                } else {
                    throw new ParseException(String.format(
                            "Expected privilege name at line %d, column %d",
                            t.line(), t.column()));
                }
            }

            if (matchKeyword("ON")) break;
            if (peek().type() == TokenType.COMMA) {
                advance();
                continue;
            }
            throw new ParseException(String.format(
                    "Expected ',' or ON at line %d, column %d",
                    peek().line(), peek().column()));
        }
    }

    private AstNode parseOnObjectClause() {
        AstNode on = new AstNode("ON_OBJECT");
        if (matchKeyword("SCHEMA")) {
            advance();
            on.addChild(new AstNode("SCHEMA_REF", consumeIdentifierOrKeywordValue()));
        } else if (matchKeyword("DATABASE")) {
            advance();
            on.addChild(new AstNode("DATABASE_REF", consumeIdentifierOrKeywordValue()));
        } else {
            if (matchKeyword("TABLE") || matchKeyword("SEQUENCE")
                    || matchKeywordOrIdent("FUNCTION") || matchKeywordOrIdent("PROCEDURE")) {
                on.addChild(new AstNode("OBJECT_KIND", advance().value().toUpperCase()));
            }
            if (peek().type() == TokenType.IDENTIFIER
                    || (peek().type() == TokenType.KEYWORD
                    && !matchKeyword("TO") && !matchKeyword("FROM"))) {
                on.addChild(parseTableRef());
            }
        }
        return on;
    }

    private AstNode parsePrincipalChild(String nodeName) {
        return new AstNode(nodeName, consumeIdentifierOrKeywordValue());
    }

    private String consumeIdentifierOrKeywordValue() {
        Token t = peek();
        if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.KEYWORD) {
            return advance().value();
        }
        throw new ParseException(String.format(
                "Expected name at line %d, column %d", t.line(), t.column()));
    }

    private void skipRestOfStatement() {
        while (peek().type() != TokenType.SEMICOLON && peek().type() != TokenType.EOF) {
            advance();
        }
    }

    // =========================================================================
    // TCL — transactions
    // =========================================================================

    /**
     * Standalone explicit cursor verbs outside {@code DECLARE…BEGIN…END}.
     */
    private AstNode parseProceduralCursorStandalone() {
        ProceduralParser pp = newProceduralParser();
        pp.setPosition(pos);
        String kw = peek().value().toUpperCase();
        AstNode n = switch (kw) {
            case "OPEN" -> pp.parseOpenCursor();
            case "FETCH" -> pp.parseFetchCursor();
            case "CLOSE" -> pp.parseCloseCursor();
            default -> throw new ParseException("Expected OPEN, FETCH, or CLOSE");
        };
        pos = pp.getPosition();
        return n;
    }

    /**
     * A standalone procedural control statement (not wrapped in {@code BEGIN…END}),
     * e.g. Oracle-style {@code WHILE … LOOP … END LOOP} or {@code LOOP … END LOOP}.
     */
    private AstNode parseProceduralStandaloneStatement() {
        Token first = peek();
        String kw = first.value().toUpperCase();
        ProceduralParser pp = newProceduralParser();
        pp.setPosition(pos);
        AstNode n = switch (kw) {
            case "IF" -> pp.parseIfStatement();
            case "WHILE" -> pp.parseWhileLoop();
            case "LOOP" -> pp.parseLoop();
            case "FOR" -> pp.parseForLoop();
            case "CASE" -> pp.parseCaseStatement();
            default -> throw new ParseException("Expected procedural keyword");
        };
        pos = pp.getPosition();
        return n;
    }

    /**
     * DECLARE ... BEGIN ... END — procedural unit with optional declare section.
     */
    private AstNode parseDeclareProceduralUnit() {
        ProceduralParser pp = newProceduralParser();
        pp.setPosition(pos);
        AstNode block = pp.parseBlockFromDeclare();
        pos = pp.getPosition();
        return block;
    }

    /**
     * {@code BEGIN} may start a transaction (TCL) or a procedural {@code BEGIN...END} block.
     * <p>If a matching {@code END} exists for this {@code BEGIN}, it is parsed as a
     * {@link ProceduralParser} block; otherwise as {@code BEGIN [WORK|TRANSACTION] ...}.
     */
    private AstNode parseBeginStatement() {
        int beginPos = pos;
        consume(TokenType.KEYWORD, "BEGIN");

        if (peek().type() == TokenType.SEMICOLON) {
            return new AstNode("BEGIN_TRANSACTION_STATEMENT");
        }
        if (matchKeyword("WORK")) {
            advance();
            AstNode stmt = new AstNode("BEGIN_TRANSACTION_STATEMENT");
            parseOptionalTransactionModes(stmt);
            return stmt;
        }
        if (matchKeyword("TRANSACTION")) {
            advance();
            AstNode stmt = new AstNode("BEGIN_TRANSACTION_STATEMENT");
            parseOptionalTransactionModes(stmt);
            return stmt;
        }
        if (looksLikeBeginTransactionMode()) {
            AstNode stmt = new AstNode("BEGIN_TRANSACTION_STATEMENT");
            parseOptionalTransactionModes(stmt);
            return stmt;
        }
        if (dialect == SqlDialect.SQLSERVER && matchKeyword("TRY")) {
            // T-SQL BEGIN TRY … END TRY — delega a ProceduralParser desde beginPos
            ProceduralParser pp = newProceduralParser();
            pp.setPosition(beginPos);
            AstNode tryCatch = pp.parseTryCatchBlock();
            pos = pp.getPosition();
            return tryCatch;
        }
        if (hasMatchingEndForBlockStartingAt(beginPos)) {
            ProceduralParser pp = newProceduralParser();
            pp.setPosition(beginPos);
            AstNode block = pp.parseBlockFromBegin();
            pos = pp.getPosition();
            return block;
        }

        AstNode stmt = new AstNode("BEGIN_TRANSACTION_STATEMENT");
        parseOptionalTransactionModes(stmt);
        return stmt;
    }

    /**
     * True when the next tokens look like PostgreSQL-style {@code BEGIN READ ONLY},
     * {@code BEGIN ISOLATION LEVEL ...}, etc. (not a procedural body).
     */
    private boolean looksLikeBeginTransactionMode() {
        Token t = peek();
        if (t.type() != TokenType.KEYWORD) {
            return false;
        }
        String u = t.value().toUpperCase();
        if ("ISOLATION".equals(u) || "DEFERRABLE".equals(u)) {
            return true;
        }
        if ("NOT".equals(u)) {
            Token t2 = peekAhead(1);
            return t2.type() == TokenType.KEYWORD && t2.value().equalsIgnoreCase("DEFERRABLE");
        }
        if ("READ".equals(u)) {
            Token t2 = peekAhead(1);
            return t2.type() == TokenType.KEYWORD
                    && ("ONLY".equalsIgnoreCase(t2.value()) || "WRITE".equalsIgnoreCase(t2.value()));
        }
        return false;
    }

    /** True if {@code tokens[beginPos]} is BEGIN and has a matching END (nested BEGIN counted). */
    private boolean hasMatchingEndForBlockStartingAt(int beginPos) {
        if (beginPos < 0 || beginPos >= tokens.size()) {
            return false;
        }
        Token t = tokens.get(beginPos);
        if (t.type() != TokenType.KEYWORD || !t.value().equalsIgnoreCase("BEGIN")) {
            return false;
        }
        int depth = 1;
        for (int i = beginPos + 1; i < tokens.size(); i++) {
            Token x = tokens.get(i);
            if (x.type() == TokenType.EOF) {
                break;
            }
            if (x.type() == TokenType.KEYWORD && x.value().equalsIgnoreCase("BEGIN")) {
                depth++;
            } else if (x.type() == TokenType.KEYWORD && x.value().equalsIgnoreCase("END")) {
                depth--;
                if (depth == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private AstNode parseStartTransaction() {
        AstNode stmt = new AstNode("START_TRANSACTION_STATEMENT");
        consume(TokenType.KEYWORD, "START");
        consume(TokenType.KEYWORD, "TRANSACTION");
        parseOptionalTransactionModes(stmt);
        return stmt;
    }

    private AstNode parseCommitStatement() {
        AstNode stmt = new AstNode("COMMIT_STATEMENT");
        consume(TokenType.KEYWORD, "COMMIT");
        if (matchKeyword("WORK")) advance();
        skipRestOfStatement();
        return stmt;
    }

    /**
     * ROLLBACK [WORK] [TO [SAVEPOINT] name]
     */
    private AstNode parseRollbackStatement() {
        AstNode stmt = new AstNode("ROLLBACK_STATEMENT");
        consume(TokenType.KEYWORD, "ROLLBACK");
        if (matchKeyword("WORK")) advance();
        if (matchKeyword("TO")) {
            advance();
            if (matchKeyword("SAVEPOINT")) advance();
            stmt.addChild(new AstNode("SAVEPOINT_REF", consumeIdentifierOrKeywordValue()));
        }
        return stmt;
    }

    private AstNode parseSavepointStatement() {
        AstNode stmt = new AstNode("SAVEPOINT_STATEMENT");
        consume(TokenType.KEYWORD, "SAVEPOINT");
        stmt.addChild(new AstNode("SAVEPOINT_NAME", consumeIdentifierOrKeywordValue()));
        return stmt;
    }

    /**
     * SET TRANSACTION ... (e.g. SET TRANSACTION ISOLATION LEVEL READ COMMITTED)
     */
    private AstNode parseSetStatement() {
        consume(TokenType.KEYWORD, "SET");
        if (!matchKeyword("TRANSACTION")) {
            throw new ParseException(String.format(
                    "Expected TRANSACTION after SET at line %d, column %d",
                    peek().line(), peek().column()));
        }
        advance();
        AstNode stmt = new AstNode("SET_TRANSACTION_STATEMENT");
        parseOptionalTransactionModes(stmt);
        return stmt;
    }

    private void parseOptionalTransactionModes(AstNode parent) {
        while (peek().type() != TokenType.SEMICOLON && peek().type() != TokenType.EOF) {
            if (matchKeyword("ISOLATION")) {
                advance();
                consume(TokenType.KEYWORD, "LEVEL");
                parent.addChild(new AstNode("ISOLATION_LEVEL", readIsolationLevelName()));
            } else if (matchKeyword("READ")) {
                advance();
                if (matchKeyword("ONLY")) {
                    advance();
                    parent.addChild(new AstNode("READ_MODE", "READ_ONLY"));
                } else if (matchKeyword("WRITE")) {
                    advance();
                    parent.addChild(new AstNode("READ_MODE", "READ_WRITE"));
                } else {
                    break;
                }
            } else if (matchKeywordOrIdent("DEFERRABLE")) {
                advance();
                parent.addChild(new AstNode("DEFERRABLE"));
            } else if (matchKeyword("NOT")) {
                advance();
                if (matchKeywordOrIdent("DEFERRABLE")) {
                    advance();
                    parent.addChild(new AstNode("NOT_DEFERRABLE"));
                } else {
                    break;
                }
            } else {
                break;
            }
        }
    }

    private String readIsolationLevelName() {
        if (matchKeyword("READ")) {
            advance();
            if (matchKeyword("UNCOMMITTED")) {
                advance();
                return "READ_UNCOMMITTED";
            }
            if (matchKeyword("COMMITTED")) {
                advance();
                return "READ_COMMITTED";
            }
            throw new ParseException(String.format(
                    "Invalid isolation level at line %d, column %d",
                    peek().line(), peek().column()));
        }
        if (matchKeyword("REPEATABLE")) {
            advance();
            consume(TokenType.KEYWORD, "READ");
            return "REPEATABLE_READ";
        }
        if (matchKeyword("SERIALIZABLE")) {
            advance();
            return "SERIALIZABLE";
        }
        Token t = peek();
        if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.KEYWORD) {
            return advance().value().replace(' ', '_').toUpperCase();
        }
        throw new ParseException(String.format(
                "Expected isolation level at line %d, column %d",
                peek().line(), peek().column()));
    }

    // =========================================================================
    // SQL Server — MERGE
    // =========================================================================

    /**
     * MERGE [INTO] target_table [AS alias]
     * USING source_table [AS alias] ON condition
     * WHEN MATCHED THEN UPDATE SET ...
     * WHEN NOT MATCHED THEN INSERT ...
     * [OUTPUT ...]
     */
    private AstNode parseMergeStatement() {
        AstNode stmt = new AstNode("MERGE_STATEMENT");
        consume(TokenType.KEYWORD, "MERGE");

        if (matchKeyword("INTO")) advance();

        stmt.addChild(parseTableRef());

        // USING source
        if (matchKeyword("USING")) {
            advance();
            AstNode using = new AstNode("MERGE_USING");

            if (peek().type() == TokenType.LEFT_PAREN) {
                advance();
                using.addChild(new AstNode("SUBQUERY"));
                int depth = 1;
                while (depth > 0 && peek().type() != TokenType.EOF) {
                    if (peek().type() == TokenType.LEFT_PAREN)  depth++;
                    else if (peek().type() == TokenType.RIGHT_PAREN) depth--;
                    if (depth > 0) advance();
                }
                consume(TokenType.RIGHT_PAREN, null);
            } else {
                using.addChild(parseTableRef());
            }

            if (matchKeyword("AS") || peek().type() == TokenType.IDENTIFIER) {
                if (matchKeyword("AS")) advance();
                using.addChild(new AstNode("ALIAS", consume(TokenType.IDENTIFIER, null).value()));
            }

            stmt.addChild(using);
        }

        // ON condition
        if (matchKeyword("ON")) {
            advance();
            AstNode onClause = new AstNode("MERGE_ON");
            onClause.addChild(parseCondition());
            stmt.addChild(onClause);
        }

        // WHEN MATCHED / WHEN NOT MATCHED clauses (consume until OUTPUT or semicolon/EOF)
        while (matchKeyword("WHEN") || matchKeywordOrIdent("WHEN")) {
            advance(); // WHEN
            AstNode whenClause = new AstNode("MERGE_WHEN");

            boolean notMatched = peek().type() == TokenType.NOT;
            if (notMatched) advance(); // NOT

            if (matchKeyword("MATCHED") || matchKeywordOrIdent("MATCHED")) {
                advance(); // MATCHED
                whenClause.addChild(new AstNode(notMatched ? "NOT_MATCHED" : "MATCHED"));
            }

            // Optional AND condition
            if (peek().type() == TokenType.AND) {
                advance();
                whenClause.addChild(parseCondition());
            }

            // THEN UPDATE SET ... | THEN INSERT | THEN DELETE
            if (matchKeyword("THEN") || matchKeywordOrIdent("THEN")) {
                advance();
                Token action = peek();
                String actionUp = action.value().toUpperCase();
                if (actionUp.equals("UPDATE")) {
                    advance();
                    consume(TokenType.KEYWORD, "SET");
                    AstNode setClause = new AstNode("SET_CLAUSE");
                    setClause.addChild(parseAssignment());
                    while (peek().type() == TokenType.COMMA) {
                        advance();
                        setClause.addChild(parseAssignment());
                    }
                    whenClause.addChild(setClause);
                } else if (actionUp.equals("INSERT")) {
                    advance();
                    AstNode insertClause = new AstNode("MERGE_INSERT");
                    if (peek().type() == TokenType.LEFT_PAREN) {
                        insertClause.addChild(parseColumnRefList());
                    }
                    if (matchKeyword("VALUES")) {
                        advance();
                        insertClause.addChild(parseValueRow());
                    }
                    whenClause.addChild(insertClause);
                } else if (actionUp.equals("DELETE")) {
                    advance();
                    whenClause.addChild(new AstNode("DELETE_ACTION"));
                }
            }

            stmt.addChild(whenClause);
        }

        // Optional OUTPUT clause (SQL Server)
        if (matchKeyword("OUTPUT")) {
            stmt.addChild(parseOutputClause());
        }

        return stmt;
    }

    // =========================================================================
    // SQL Server — OUTPUT clause
    // =========================================================================

    /**
     * OUTPUT inserted.col1, deleted.col2 [INTO table]
     */
    private AstNode parseOutputClause() {
        consume(TokenType.KEYWORD, "OUTPUT");
        AstNode output = new AstNode("OUTPUT_CLAUSE");

        output.addChild(parseColumnExpr());
        while (peek().type() == TokenType.COMMA) {
            advance();
            output.addChild(parseColumnExpr());
        }

        if (matchKeyword("INTO")) {
            advance();
            output.addChild(parseTableRef());
        }

        return output;
    }

    /** PRINT expression */
    private AstNode parsePrintStatement() {
        AstNode stmt = new AstNode("PRINT_STATEMENT");
        consume(TokenType.KEYWORD, "PRINT");
        if (peek().type() != TokenType.SEMICOLON && peek().type() != TokenType.EOF) {
            stmt.addChild(parseValueExpr());
        }
        return stmt;
    }

    /** RAISERROR(msg, severity, state) */
    private AstNode parseRaiserrorStatement() {
        AstNode stmt = new AstNode("RAISERROR_STATEMENT");
        consume(TokenType.KEYWORD, "RAISERROR");
        consumeBalancedParens(stmt);
        return stmt;
    }

    /** T-SQL-only top-level statements routed from {@link #parse()}. */
    private AstNode parseTsqlTopLevelStatement() {
        if (dialect != SqlDialect.SQLSERVER) {
            throw new ParseException(String.format(
                    "Statement '%s' is only valid for SQL Server (T-SQL) dialect",
                    peek().value()));
        }
        TSqlParser pp = new TSqlParser(tokens, dialect);
        pp.setPosition(pos);
        String kw = peek().value().toUpperCase(Locale.ROOT);
        AstNode n = switch (kw) {
            case "THROW" -> pp.parseThrowStatementNode();
            case "GOTO" -> pp.parseGotoStatement();
            case "DEALLOCATE" -> pp.parseDeallocateStatement();
            case "WAITFOR" -> pp.parseWaitforStatement();
            default -> throw new ParseException("Unsupported T-SQL statement: " + kw);
        };
        pos = pp.getPosition();
        return n;
    }

    /** {@code EXEC|EXECUTE} including {@code sp_executesql}. */
    private AstNode parseExecStatement() {
        if (dialect != SqlDialect.SQLSERVER) {
            throw new ParseException(String.format(
                    "EXEC / EXECUTE requires SQL Server dialect at line %d",
                    peek().line()));
        }
        advance();
        AstNode n = new AstNode("EXEC_STATEMENT");
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (peek().type() != TokenType.EOF) {
            if (paren == 0 && peek().type() == TokenType.SEMICOLON) {
                break;
            }
            Token t = peek();
            if (t.type() == TokenType.LEFT_PAREN) {
                paren++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                paren--;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(advance().value());
        }
        n.addChild(new AstNode("EXPRESSION", sb.toString().trim()));
        return n;
    }

    // =========================================================================
    // Oracle — FLASHBACK / PURGE
    // =========================================================================

    /**
     * FLASHBACK TABLE table TO {SCN n | TIMESTAMP t}
     * FLASHBACK DATABASE TO {SCN n | TIMESTAMP t}
     */
    private AstNode parseFlashbackQuery() {
        AstNode stmt = new AstNode("FLASHBACK_STATEMENT");
        consume(TokenType.KEYWORD, "FLASHBACK");

        Token target = peek();
        if (target.type() == TokenType.KEYWORD || target.type() == TokenType.IDENTIFIER) {
            stmt.addChild(new AstNode("FLASHBACK_TARGET", advance().value().toUpperCase()));
        }

        stmt.addChild(parseTableRef());

        if (matchKeyword("TO")) {
            advance();
            Token scnOrTs = peek();
            if (scnOrTs.type() == TokenType.IDENTIFIER || scnOrTs.type() == TokenType.KEYWORD) {
                String kind = advance().value().toUpperCase(); // SCN | TIMESTAMP
                AstNode toClause = new AstNode("FLASHBACK_TO", kind);
                if (peek().type() != TokenType.SEMICOLON && peek().type() != TokenType.EOF) {
                    toClause.addChild(parseValueExpr());
                }
                stmt.addChild(toClause);
            }
        }

        return stmt;
    }

    /**
     * PURGE TABLE name | PURGE INDEX name | PURGE RECYCLEBIN | PURGE DBA_RECYCLEBIN
     */
    private AstNode parsePurgeStatement() {
        AstNode stmt = new AstNode("PURGE_STATEMENT");
        consume(TokenType.KEYWORD, "PURGE");

        Token target = peek();
        if (target.type() == TokenType.KEYWORD || target.type() == TokenType.IDENTIFIER) {
            String kind = advance().value().toUpperCase();
            stmt.addChild(new AstNode("PURGE_TARGET", kind));
            if ((kind.equals("TABLE") || kind.equals("INDEX"))
                    && peek().type() != TokenType.SEMICOLON
                    && peek().type() != TokenType.EOF) {
                stmt.addChild(parseTableRef());
            }
        }

        return stmt;
    }

    // =========================================================================
    // PostgreSQL — COPY / VACUUM / ANALYZE
    // =========================================================================

    /**
     * COPY table FROM 'file' | COPY table TO 'file' | COPY table FROM STDIN
     */
    private AstNode parseCopyStatement() {
        AstNode stmt = new AstNode("COPY_STATEMENT");
        consume(TokenType.KEYWORD, "COPY");
        stmt.addChild(parseTableRef());

        Token direction = peek();
        if (direction.type() == TokenType.KEYWORD
                && (direction.value().equalsIgnoreCase("FROM")
                    || direction.value().equalsIgnoreCase("TO"))) {
            stmt.addChild(new AstNode("COPY_DIRECTION", advance().value().toUpperCase()));
        }

        // Source / destination — string literal or STDIN/STDOUT
        Token src = peek();
        if (src.type() == TokenType.STRING) {
            stmt.addChild(new AstNode("COPY_SOURCE", advance().value()));
        } else if (src.type() == TokenType.IDENTIFIER || src.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("COPY_SOURCE", advance().value().toUpperCase()));
        }

        // Optional WITH options — just consume rest of statement
        if (matchKeywordOrIdent("WITH")) {
            advance();
            consumeBalancedParens(stmt);
        }

        return stmt;
    }

    /** VACUUM [FULL] [FREEZE] [VERBOSE] [table] */
    private AstNode parseVacuumStatement() {
        AstNode stmt = new AstNode("VACUUM_STATEMENT");
        consume(TokenType.KEYWORD, "VACUUM");

        // Optional modifiers
        while (matchKeywordOrIdent("FULL") || matchKeywordOrIdent("FREEZE")
                || matchKeywordOrIdent("VERBOSE") || matchKeyword("ANALYZE")) {
            stmt.addChild(new AstNode("VACUUM_OPTION", advance().value().toUpperCase()));
        }

        // Optional table
        if (peek().type() == TokenType.IDENTIFIER) {
            stmt.addChild(parseTableRef());
        }

        return stmt;
    }

    /** ANALYZE [VERBOSE] [table] */
    private AstNode parseAnalyzeStatement() {
        AstNode stmt = new AstNode("ANALYZE_STATEMENT");
        consume(TokenType.KEYWORD, "ANALYZE");

        if (matchKeywordOrIdent("VERBOSE")) {
            advance();
            stmt.addChild(new AstNode("VERBOSE"));
        }

        if (peek().type() == TokenType.IDENTIFIER) {
            stmt.addChild(parseTableRef());
        }

        return stmt;
    }

    // =========================================================================
    // SQLite — PRAGMA / ATTACH / DETACH
    // =========================================================================

    /**
     * PRAGMA name [= value | (value)]
     */
    private AstNode parsePragmaStatement() {
        AstNode stmt = new AstNode("PRAGMA_STATEMENT");
        consume(TokenType.KEYWORD, "PRAGMA");

        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("PRAGMA_NAME", advance().value()));
        }

        if (peek().type() == TokenType.EQUALS) {
            advance();
            stmt.addChild(new AstNode("PRAGMA_VALUE", advance().value()));
        } else if (peek().type() == TokenType.LEFT_PAREN) {
            advance();
            stmt.addChild(new AstNode("PRAGMA_VALUE", advance().value()));
            consume(TokenType.RIGHT_PAREN, null);
        }

        return stmt;
    }

    /**
     * ATTACH [DATABASE] 'file' AS name
     */
    private AstNode parseAttachStatement() {
        AstNode stmt = new AstNode("ATTACH_STATEMENT");
        consume(TokenType.KEYWORD, "ATTACH");

        if (matchKeywordOrIdent("DATABASE")) advance();

        Token file = peek();
        if (file.type() == TokenType.STRING || file.type() == TokenType.IDENTIFIER) {
            stmt.addChild(new AstNode("ATTACH_FILE", advance().value()));
        }

        if (matchKeyword("AS")) {
            advance();
            Token alias = peek();
            if (alias.type() == TokenType.IDENTIFIER || alias.type() == TokenType.KEYWORD) {
                stmt.addChild(new AstNode("ATTACH_ALIAS", advance().value()));
            }
        }

        return stmt;
    }

    /**
     * DETACH [DATABASE] name
     */
    private AstNode parseDetachStatement() {
        AstNode stmt = new AstNode("DETACH_STATEMENT");
        consume(TokenType.KEYWORD, "DETACH");

        if (matchKeywordOrIdent("DATABASE")) advance();

        Token name = peek();
        if (name.type() == TokenType.IDENTIFIER || name.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("DETACH_ALIAS", advance().value()));
        }

        return stmt;
    }

    // =========================================================================
    // Utility — consume balanced parens (for complex expressions we don't parse)
    // =========================================================================

    /**
     * MySQL / MariaDB <strong>client</strong> directive (mysql CLI). Not server SQL.
     */
    private AstNode parseClientDelimiterStatement() {
        if (dialect != SqlDialect.MYSQL) {
            throw new ParseException(String.format(
                    "DELIMITER is only valid in MySQL client scripts (line %d)",
                    peek().line()));
        }
        consume(TokenType.KEYWORD, "DELIMITER");
        AstNode stmt = new AstNode("CLIENT_DELIMITER_STATEMENT");
        StringBuilder value = new StringBuilder();
        if (peek().type() == TokenType.SEMICOLON) {
            value.append(';');
            advance();
        } else {
            while (peek().type() != TokenType.EOF) {
                Token t = peek();
                if (value.length() > 0 && startsSqlStatementKeyword(t)) {
                    break;
                }
                if (startsSqlStatementKeyword(t) && value.length() == 0) {
                    throw new ParseException(String.format(
                            "Expected delimiter text after DELIMITER at line %d, column %d",
                            t.line(), t.column()));
                }
                value.append(advance().value());
            }
        }
        String v = value.toString().trim();
        stmt.addChild(new AstNode("DELIMITER_VALUE", v.isEmpty() ? ";" : v));
        return stmt;
    }

    /** True when {@code t} begins a new top-level statement (end of delimiter value on same line). */
    private boolean startsSqlStatementKeyword(Token t) {
        if (t.type() != TokenType.KEYWORD) {
            return false;
        }
        String u = t.value().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "CREATE", "SELECT", "INSERT", "UPDATE", "DELETE", "WITH", "DROP", "ALTER",
                    "SHOW", "REPLACE", "DESCRIBE", "GRANT", "REVOKE", "BEGIN", "DECLARE",
                    "DELIMITER", "CALL", "USE", "SET" -> true;
            default -> false;
        };
    }

    private void consumeBalancedParens(AstNode parent) {
        if (peek().type() != TokenType.LEFT_PAREN) return;
        advance();
        AstNode args = new AstNode("RAW_ARGS");
        int depth = 1;
        while (depth > 0 && peek().type() != TokenType.EOF) {
            if (peek().type() == TokenType.LEFT_PAREN)  depth++;
            else if (peek().type() == TokenType.RIGHT_PAREN) depth--;
            if (depth > 0) {
                args.addChild(new AstNode("TOKEN", advance().value()));
            }
        }
        consume(TokenType.RIGHT_PAREN, null);
        parent.addChild(args);
    }

    // =========================================================================
    // Public error reporter
    // =========================================================================

    public List<String> getSyntaxErrors() {
        return List.copyOf(syntaxErrors);
    }

    // =========================================================================
    // Token stream helpers
    // =========================================================================

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekAhead(int offset) {
        int idx = pos + offset;
        return (idx < tokens.size()) ? tokens.get(idx) : tokens.get(tokens.size() - 1);
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (t.type() != TokenType.EOF) pos++;
        return t;
    }

    /** Logical {@code OR} is lexed as {@link TokenType#OR}, not {@link TokenType#KEYWORD}. */
    private void consumeOrKeyword() {
        Token t = peek();
        if ((t.type() == TokenType.OR || t.type() == TokenType.KEYWORD)
                && "OR".equalsIgnoreCase(t.value())) {
            advance();
            return;
        }
        throw new ParseException(String.format(
                "Expected 'OR' but found '%s' (%s) at line %d, column %d",
                t.value(), t.type(), t.line(), t.column()));
    }

    private Token consume(TokenType expectedType, String expectedValue) {
        Token t = peek();

        if (expectedType != null && t.type() != expectedType) {
            String msg = String.format(
                    "Expected %s%s but found '%s' (%s) at line %d, column %d",
                    expectedType,
                    expectedValue != null ? " '" + expectedValue + "'" : "",
                    t.value(), t.type(), t.line(), t.column());
            syntaxErrors.add(msg);
            throw new ParseException(msg);
        }

        if (expectedValue != null && !t.value().equalsIgnoreCase(expectedValue)) {
            String msg = String.format(
                    "Expected '%s' but found '%s' at line %d, column %d",
                    expectedValue, t.value(), t.line(), t.column());
            syntaxErrors.add(msg);
            throw new ParseException(msg);
        }

        return advance();
    }

    private boolean matchKeyword(String name) {
        Token t = peek();
        return t.type() == TokenType.KEYWORD && t.value().equalsIgnoreCase(name);
    }

    private boolean matchKeywordOrIdent(String name) {
        Token t = peek();
        return (t.type() == TokenType.KEYWORD || t.type() == TokenType.IDENTIFIER)
                && t.value().equalsIgnoreCase(name);
    }

    private boolean isComparisonOp(Token t) {
        return switch (t.type()) {
            case EQUALS, NOT_EQUALS, LESS_THAN, GREATER_THAN, LESS_EQUAL, GREATER_EQUAL -> true;
            default -> false;
        };
    }
}
