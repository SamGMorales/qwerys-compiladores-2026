package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.Token;
import com.qwerys.qwerys_backend.analyzer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Generic procedural block parser (foundation for PL/SQL, T-SQL, PL/pgSQL, SQL/PSM).
 * Parses {@code DECLARE ... BEGIN ... END} and {@code BEGIN ... END} shapes.
 */
public class ProceduralParser {

    private final List<Token> tokens;
    private final SqlDialect dialect;
    private int pos;

    public ProceduralParser(List<Token> tokens, SqlDialect dialect) {
        this.tokens = tokens.stream()
                .filter(t -> t.type() != TokenType.WHITESPACE)
                .collect(Collectors.toCollection(ArrayList::new));
        this.dialect = dialect;
        this.pos = 0;
    }

    public void setPosition(int p) {
        this.pos = p;
    }

    public int getPosition() {
        return pos;
    }

    public SqlDialect getDialect() {
        return dialect;
    }

    /**
     * Entry: parses {@code DECLARE...BEGIN...END} when the stream starts with DECLARE,
     * or {@code BEGIN...END} when it starts with BEGIN.
     */
    public AstNode parseBlock() {
        if (matchKeyword("DECLARE")) {
            return parseBlockFromDeclare();
        }
        if (matchKeyword("BEGIN")) {
            return parseBlockFromBegin();
        }
        Token t = peek();
        throw new SqlParser.ParseException(String.format(
                "Expected DECLARE or BEGIN at line %d, column %d",
                t.line(), t.column()));
    }

    /** Stream must start at DECLARE. */
    public AstNode parseBlockFromDeclare() {
        AstNode root = new AstNode("BLOCK_STATEMENT");
        consumeKeyword("DECLARE");
        AstNode declareSection = new AstNode("DECLARE_SECTION");
        while (true) {
            if (peek().type() == TokenType.EOF) {
                break;
            }
            if (isKeyword("BEGIN")) {
                break;
            }
            if (leadsExecutableProceduralKeyword()) {
                break;
            }
            if (isKeyword("EXIT") || isKeyword("CONTINUE")) {
                declareSection.addChild(parseExitContinueHandlerDeclaration());
                continue;
            }
            declareSection.addChild(parseVariableDeclaration());
        }
        root.addChild(declareSection);

        if (isKeyword("BEGIN")) {
            consumeKeyword("BEGIN");
            parseBlockBodyAfterBegin(root);
            consumeOptionalTrailingSemicolon();
            return root;
        }

        root.addChild(parseStatementList(StatementListMode.SCRIPT_FRAGMENT));
        return root;
    }

    /** Stream must start at BEGIN (outer block, no leading DECLARE section in this span). */
    public AstNode parseBlockFromBegin() {
        AstNode root = new AstNode("BLOCK_STATEMENT");
        consumeKeyword("BEGIN");
        parseBlockBodyAfterBegin(root);
        consumeOptionalTrailingSemicolon();
        return root;
    }

    /** {@code EXCEPTION WHEN ... THEN ...} section (consumes {@code EXCEPTION} first). */
    public AstNode parseExceptionBlock() {
        consumeKeyword("EXCEPTION");
        AstNode block = new AstNode("EXCEPTION_BLOCK");
        while (matchKeyword("WHEN")) {
            AstNode handler = parseExceptionWhenHandler();
            block.addChild(handler);
        }
        return block;
    }

    /** {@code RAISE}; {@code RAISE name}; Oracle {@code RAISE_APPLICATION_ERROR(...)} — consumes trailing {@code ;} if present. */
    public AstNode parseRaiseStatement() {
        consumeKeyword("RAISE");
        AstNode stmt = new AstNode("RAISE_STATEMENT");
        Token ahead = peek();
        if (ahead.type() == TokenType.IDENTIFIER
                && "RAISE_APPLICATION_ERROR".equalsIgnoreCase(ahead.value())) {
            advance();
            consume(TokenType.LEFT_PAREN);
            StringBuilder sb = new StringBuilder();
            int depth = 1;
            while (depth > 0 && peek().type() != TokenType.EOF) {
                Token x = advance();
                if (x.type() == TokenType.LEFT_PAREN) {
                    depth++;
                } else if (x.type() == TokenType.RIGHT_PAREN) {
                    depth--;
                }
                sb.append(x.value());
            }
            stmt.addChild(new AstNode("APPLICATION_ERROR_INVOCATION", sb.toString()));
            consumeOptionalTrailingSemicolon();
            return stmt;
        }
        if (ahead.type() == TokenType.KEYWORD && "NOTICE".equalsIgnoreCase(ahead.value())) {
            stmt.addChild(new AstNode("RAISE_KIND", advance().value()));
            ahead = peek();
        }
        if (ahead.type() == TokenType.SEMICOLON) {
            consumeOptionalTrailingSemicolon();
            return new AstNode("RAISE_STATEMENT", "");
        }
        String raised = consumeIdentifierOrKeywordValue();
        stmt.addChild(new AstNode("EXCEPTION_NAME", raised));
        consumeOptionalTrailingSemicolon();
        return stmt;
    }

    /** {@code DECLARE EXIT|CONTINUE HANDLER FOR cond_list statement_or_begin_block } */
    protected AstNode parseExitContinueHandlerDeclaration() {
        String kw = peek().value().toUpperCase(Locale.ROOT);
        consumeKeyword(kw.equals("EXIT") ? "EXIT" : "CONTINUE");
        consumeKeyword("HANDLER");
        consumeKeyword("FOR");
        AstNode h = new AstNode("HANDLER_DECLARATION", kw);
        AstNode cond = parseHandlerConditionListUntilBody();
        h.addChild(cond);
        if (isKeyword("BEGIN")) {
            AstNode innerBlock = new AstNode("BLOCK_STATEMENT");
            consumeKeyword("BEGIN");
            parseBlockBodyAfterBegin(innerBlock);
            consumeOptionalTrailingSemicolon();
            h.addChild(innerBlock);
        } else {
            AstNode list = new AstNode("STATEMENT_LIST");
            list.addChild(parseStatementInBlock(StatementListMode.SCRIPT_FRAGMENT));
            consumeOptionalTrailingSemicolon();
            h.addChild(list);
        }
        return h;
    }

    private AstNode parseHandlerConditionListUntilBody() {
        StringBuilder accum = new StringBuilder();
        int parenDepth = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (handlerBodyStartsHere(parenDepth)) {
                break;
            }
            if (t.type() == TokenType.LEFT_PAREN) {
                parenDepth++;
            } else if (t.type() == TokenType.RIGHT_PAREN && parenDepth > 0) {
                parenDepth--;
            }
            if (accum.length() > 0) {
                accum.append(' ');
            }
            accum.append(advance().value());
        }
        String s = accum.toString().trim();
        return new AstNode("HANDLER_FOR_CONDITIONS", s);
    }

    private boolean handlerBodyStartsHere(int parenDepth) {
        if (parenDepth != 0) {
            return false;
        }
        if (isKeyword("BEGIN")) {
            return true;
        }
        return handlerSingleStmtStarter();
    }

    private boolean handlerSingleStmtStarter() {
        if (peek().type() != TokenType.KEYWORD && peek().type() != TokenType.IDENTIFIER) {
            return false;
        }
        String u = peek().value().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "SET", "SELECT", "INSERT", "UPDATE", "DELETE", "WITH", "CALL", "EXEC", "EXECUTE",
                    "SIGNAL", "RESIGNAL", "DO", "GET", "SHOW", "REPLACE", "LEAVE", "ITERATE",
                    "RETURN", "REPEAT" -> true;
            default -> false;
        };
    }

    protected void parseBlockBodyAfterBegin(AstNode root) {
        prependInnerDeclareSection(root);
        AstNode main = parseStatementList(StatementListMode.BLOCK_MAIN);
        root.addChild(main);
        if (isKeyword("EXCEPTION")) {
            root.addChild(parseExceptionBlock());
        }
        consumeKeyword("END");
        consumeOptionalEndNameLabel();
    }

    /**
     * T-SQL / MySQL allow {@code DECLARE} immediately after {@code BEGIN}; PL/pgSQL usually declares
     * before {@code BEGIN} in the same lexer span — overridden per dialect.
     */
    protected void prependInnerDeclareSection(AstNode root) {
    }

    /** Oracle PL/SQL optional {@code END label;} — default does nothing (anonymous blocks). */
    protected void consumeOptionalEndNameLabel() {
    }

    private AstNode parseExceptionWhenHandler() {
        AstNode handler = new AstNode("EXCEPTION_HANDLER");
        consumeKeyword("WHEN");
        AstNode cond = parseExpressionUntilKeyword("THEN");
        handler.addChild(wrapCondition(cond));
        consumeKeyword("THEN");
        AstNode body = parseStatementList(StatementListMode.EXCEPTION_HANDLER_BODY);
        handler.addChild(body);
        return handler;
    }

    public AstNode parseTryCatchBlock() {
        consumeKeyword("BEGIN");
        consumeKeyword("TRY");
        AstNode ex = new AstNode("EXCEPTION_BLOCK", "TRY_CATCH");
        AstNode trySection = new AstNode("TRY_SECTION");
        trySection.addChild(parseStatementList(StatementListMode.TRY_BODY));
        consumeKeyword("END");
        consumeKeyword("TRY");
        consumeOptionalTrailingSemicolon();
        ex.addChild(trySection);
        // El bloque CATCH es opcional — su ausencia es reportada por TSqlAnalyzer PROC-SS-002
        if (isKeyword("BEGIN") && isPeekAheadKeyword(1, "CATCH")) {
            consumeKeyword("BEGIN");
            consumeKeyword("CATCH");
            AstNode catchSection = new AstNode("CATCH_SECTION");
            catchSection.addChild(parseStatementList(StatementListMode.CATCH_BODY));
            consumeKeyword("END");
            consumeKeyword("CATCH");
            consumeOptionalTrailingSemicolon();
            ex.addChild(catchSection);
        }
        return ex;
    }

    protected AstNode parseNestedBlockStatement() {
        AstNode inner = new AstNode("BLOCK_STATEMENT");
        consumeKeyword("BEGIN");
        parseBlockBodyAfterBegin(inner);
        consumeOptionalTrailingSemicolon();
        return inner;
    }

    protected void consumeOptionalTrailingSemicolon() {
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
    }

    /** T-SQL scripts / procedure bodies without an outer {@code BEGIN…END}. */
    protected AstNode parseScriptFragments() {
        return parseStatementList(StatementListMode.SCRIPT_FRAGMENT);
    }

    /** Hook for dialect-specific statements (e.g. T-SQL {@code DEALLOCATE}, {@code THROW}). */
    protected AstNode tryParseDialectProceduralStatement(StatementListMode contextMode) {
        return null;
    }

    // -------------------------------------------------------------------------
    // DECLARE
    // -------------------------------------------------------------------------

    protected AstNode parseVariableDeclaration() {
        if (isKeyword("CURSOR")) {
            consumeKeyword("CURSOR");
            String cursorName = consumeIdentifierOrKeywordValue();
            return finishCursorDeclaration(cursorName);
        }
        String name = consumeIdentifierOrKeywordValue();
        if (isKeyword("CURSOR")) {
            consumeKeyword("CURSOR");
            return finishCursorDeclaration(name);
        }
        AstNode decl = new AstNode("VARIABLE_DECLARATION", name);
        decl.addChild(parseTypeNode());
        if (isKeyword("DEFAULT")) {
            advance();
            AstNode def = new AstNode("DEFAULT_VALUE");
            def.addChild(parseExpressionNode());
            decl.addChild(def);
        }
        consume(TokenType.SEMICOLON);
        return decl;
    }

    /**
     * {@code DECLARE name CURSOR FOR select ...;} — SELECT stored as {@code EXPRESSION} child.
     */
    public AstNode parseDeclareCursor(String name) {
        consumeKeyword("CURSOR");
        return finishCursorDeclaration(name);
    }

    /**
     * After {@code CURSOR name}, consumes the keyword introducing the query ({@code FOR} generically,
     * or {@code IS} in Oracle PL/SQL) and the statement text until {@code ;}.
     */
    protected AstNode finishCursorDeclaration(String cursorName) {
        consumeCursorSelectIntro();
        AstNode decl = new AstNode("CURSOR_DECLARATION", cursorName);
        decl.addChild(parseSqlFragmentUntilSemicolon());
        return decl;
    }

    /** Generic ISO/SQL: {@code FOR}; Oracle PL/SQL overrides with {@code IS | FOR}. */
    protected void consumeCursorSelectIntro() {
        consumeKeyword("FOR");
    }

    /** Parses a SQL fragment until terminating {@code ;} at paren depth 0 (subqueries safe). */
    private AstNode parseSqlFragmentUntilSemicolon() {
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (paren == 0 && t.type() == TokenType.SEMICOLON) {
                break;
            }
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
        AstNode ex = new AstNode("EXPRESSION", sb.toString().trim());
        consume(TokenType.SEMICOLON);
        return ex;
    }

    /** {@code OPEN cursor_name;} */
    public AstNode parseOpenCursor() {
        consumeKeyword("OPEN");
        String name = consumeIdentifierOrKeywordValue();
        AstNode n = new AstNode("OPEN_CURSOR_STATEMENT", name);
        consumeOptionalSemicolonFollowingStatement();
        return n;
    }

    /** {@code FETCH cursor INTO v1 [, v2 ...];} */
    public AstNode parseFetchCursor() {
        consumeKeyword("FETCH");
        String name = consumeIdentifierOrKeywordValue();
        consumeKeyword("INTO");
        AstNode n = new AstNode("FETCH_STATEMENT", name);
        while (true) {
            n.addChild(new AstNode("TARGET", consumeIdentifierOrKeywordValue()));
            if (peek().type() == TokenType.COMMA) {
                advance();
            } else {
                break;
            }
        }
        consume(TokenType.SEMICOLON);
        return n;
    }

    /** {@code CLOSE cursor_name;} */
    public AstNode parseCloseCursor() {
        consumeKeyword("CLOSE");
        String name = consumeIdentifierOrKeywordValue();
        AstNode n = new AstNode("CLOSE_CURSOR_STATEMENT", name);
        consumeOptionalSemicolonFollowingStatement();
        return n;
    }

    private void consumeOptionalSemicolonFollowingStatement() {
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
    }

    protected AstNode parseTypeNode() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF || t.type() == TokenType.SEMICOLON) {
                break;
            }
            if (t.type() == TokenType.KEYWORD && "DEFAULT".equalsIgnoreCase(t.value())) {
                break;
            }
            if (t.type() == TokenType.OPERATOR && "%".equals(t.value())) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(advance().value());
                Token next = peek();
                if (next.type() == TokenType.KEYWORD || next.type() == TokenType.IDENTIFIER) {
                    String k = next.value().toUpperCase(Locale.ROOT);
                    if ("TYPE".equals(k) || "ROWTYPE".equals(k)) {
                        sb.append(advance().value());
                        continue;
                    }
                }
                break;
            }
            if (t.type() == TokenType.KEYWORD || t.type() == TokenType.IDENTIFIER) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(advance().value());
            } else if (t.type() == TokenType.DOT) {
                sb.append(advance().value());
            } else if (t.type() == TokenType.LEFT_PAREN) {
                sb.append(advance().value());
                int depth = 1;
                while (depth > 0 && peek().type() != TokenType.EOF) {
                    Token x = advance();
                    sb.append(x.value());
                    if (x.type() == TokenType.LEFT_PAREN) {
                        depth++;
                    } else if (x.type() == TokenType.RIGHT_PAREN) {
                        depth--;
                    }
                }
            } else {
                break;
            }
        }
        String typeStr = sb.toString().trim();
        if (typeStr.isEmpty()) {
            throw new SqlParser.ParseException(String.format(
                    "Expected type after variable name at line %d, column %d",
                    peek().line(), peek().column()));
        }
        return new AstNode("TYPE", typeStr);
    }

    // -------------------------------------------------------------------------
    // Statement list
    // -------------------------------------------------------------------------

    protected enum StatementListMode {
        /** {@code BEGIN}-body main section stops before bare {@code END} or {@code EXCEPTION}. */
        BLOCK_MAIN,
        /** PL/SQL handler body ends before another {@code WHEN} or bare block {@code END}. */
        EXCEPTION_HANDLER_BODY,
        /** T-SQL {@code BEGIN TRY … END TRY}. */
        TRY_BODY,
        /** T-SQL {@code BEGIN CATCH … END CATCH}. */
        CATCH_BODY,
        /**
         * {@code DECLARE … OPEN … FETCH …} scripts without enclosing {@code BEGIN…END}: runs until EOF
         * ({@code ;} separators only).
         */
        SCRIPT_FRAGMENT,
        /** {@code IF} branch — stops before {@code ELSIF}, {@code ELSEIF}, {@code ELSE}, {@code END IF}. */
        IF_BRANCH,
        /** {@code WHILE … DO … END WHILE}. */
        WHILE_DO,
        /** {@code WHILE … LOOP … END LOOP} / {@code FOR … LOOP} / plain {@code LOOP}. */
        LOOP_BODY,
        /** {@code CASE} branch before next {@code WHEN}, {@code ELSE}, or {@code END CASE}. */
        CASE_BRANCH,
        /** {@code CASE … ELSE} branch until {@code END CASE}. */
        CASE_ELSE,
        /** MySQL: {@code REPEAT … UNTIL … END REPEAT}. */
        REPEAT_BODY,
        /** T-SQL: {@code WHILE cond BEGIN … END}. */
        WHILE_BEGIN_BODY
    }

    protected AstNode parseStatementList(StatementListMode mode) {
        AstNode list = new AstNode("STATEMENT_LIST");
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (t.type() == TokenType.SEMICOLON) {
                advance();
                continue;
            }
            if (atStatementListTerminator(mode)) {
                break;
            }
            list.addChild(parseStatementInBlock(mode));
        }
        return list;
    }

    private boolean atStatementListTerminator(StatementListMode mode) {
        return switch (mode) {
            case BLOCK_MAIN -> isBareEnd() || matchKeyword("EXCEPTION");
            case EXCEPTION_HANDLER_BODY -> isKeyword("WHEN") || isBareEnd();
            case TRY_BODY -> isKeyword("END") && isPeekAheadKeyword(1, "TRY");
            case CATCH_BODY -> isKeyword("END") && isPeekAheadKeyword(1, "CATCH");
            case SCRIPT_FRAGMENT -> false;
            case IF_BRANCH -> atIfBranchTerminator();
            case WHILE_DO -> isKeyword("END") && isPeekAheadKeyword(1, "WHILE");
            case LOOP_BODY -> isKeyword("END") && isPeekAheadKeyword(1, "LOOP");
            case CASE_BRANCH -> isKeyword("WHEN")
                    || (isKeyword("ELSE") && !isPeekAheadKeyword(1, "IF"))
                    || (isKeyword("END") && isPeekAheadKeyword(1, "CASE"));
            case CASE_ELSE -> isKeyword("END") && isPeekAheadKeyword(1, "CASE");
            case REPEAT_BODY -> isKeyword("UNTIL");
            case WHILE_BEGIN_BODY -> isBareEnd();
        };
    }

    /** True when the next token starts an executable procedural statement (no {@code BEGIN} wrapper). */
    protected boolean leadsExecutableProceduralKeyword() {
        Token t = peek();
        if (t.type() != TokenType.KEYWORD) {
            return false;
        }
        return switch (t.value().toUpperCase(Locale.ROOT)) {
            case "OPEN", "FETCH", "CLOSE", "IF", "WHILE", "LOOP", "FOR", "CASE",
                    "SET", "BEGIN", "RAISE", "LEAVE", "ITERATE", "REPEAT" -> true;
            default -> false;
        };
    }

    /** {@code END} that closes {@code BEGIN} / outer block, not {@code END IF} / {@code END LOOP} / … */
    private boolean isBareEnd() {
        if (!isKeyword("END")) {
            return false;
        }
        if (isPeekAheadKeyword(1, "IF")
                || isPeekAheadKeyword(1, "WHILE")
                || isPeekAheadKeyword(1, "LOOP")
                || isPeekAheadKeyword(1, "CASE")
                || isPeekAheadKeyword(1, "TRY")
                || isPeekAheadKeyword(1, "CATCH")) {
            return false;
        }
        return true;
    }

    private boolean atIfBranchTerminator() {
        if (isKeyword("ELSIF") || isKeyword("ELSEIF")) {
            return true;
        }
        if (isKeyword("ELSE")) {
            return true;
        }
        return isKeyword("END") && isPeekAheadKeyword(1, "IF");
    }

    /**
     * After {@code BEGIN}, detect clauses that belong to TCL-style {@code BEGIN …} transactions
     * rather than procedural {@code BEGIN … END}.
     */
    private boolean beginsTransactionStyleBegin() {
        Token next = peekAhead(1);
        if (next == null || next.type() != TokenType.KEYWORD) {
            return false;
        }
        String u = next.value().toUpperCase(Locale.ROOT);
        return "TRANSACTION".equals(u)
                || "WORK".equals(u)
                || "ISOLATION".equals(u)
                || "NOT".equals(u)
                || "READ".equals(u)
                || "DEFERRABLE".equals(u)
                || "IMMEDIATE".equals(u)
                || "DEFERRED".equals(u)
                || "SERIALIZABLE".equals(u);
    }

    protected boolean isPeekAheadKeyword(int offset, String kw) {
        Token t = peekAhead(offset);
        return t != null && t.type() == TokenType.KEYWORD && t.value().equalsIgnoreCase(kw);
    }

    protected AstNode parseStatementInBlock(StatementListMode contextMode) {
        AstNode dialectStmt = tryParseDialectProceduralStatement(contextMode);
        if (dialectStmt != null) {
            return dialectStmt;
        }
        Token t = peek();
        if (t.type() == TokenType.KEYWORD && "BEGIN".equalsIgnoreCase(t.value())
                && isPeekAheadKeyword(1, "TRY")) {
            return parseTryCatchBlock();
        }
        if (t.type() == TokenType.KEYWORD && "BEGIN".equalsIgnoreCase(t.value())
                && beginsTransactionStyleBegin()) {
            return parseRawStatementUntilEndOrSemi(contextMode);
        }
        if (t.type() == TokenType.KEYWORD && "BEGIN".equalsIgnoreCase(t.value())) {
            return parseNestedBlockStatement();
        }
        if (t.type() == TokenType.KEYWORD && "IF".equalsIgnoreCase(t.value())) {
            return parseIfStatement();
        }
        if (t.type() == TokenType.KEYWORD && "WHILE".equalsIgnoreCase(t.value())) {
            return parseWhileLoop();
        }
        if (t.type() == TokenType.KEYWORD && "FOR".equalsIgnoreCase(t.value())) {
            return parseForLoop();
        }
        if (t.type() == TokenType.KEYWORD && "LOOP".equalsIgnoreCase(t.value())) {
            return parseLoop();
        }
        if (t.type() == TokenType.KEYWORD && "CASE".equalsIgnoreCase(t.value())) {
            return parseCaseStatement();
        }
        if (t.type() == TokenType.KEYWORD && "EXIT".equalsIgnoreCase(t.value())) {
            return parseExitWhen();
        }

        if (t.type() == TokenType.KEYWORD && "OPEN".equalsIgnoreCase(t.value())) {
            return parseOpenCursor();
        }
        if (t.type() == TokenType.KEYWORD && "FETCH".equalsIgnoreCase(t.value())) {
            return parseFetchCursor();
        }
        if (t.type() == TokenType.KEYWORD && "CLOSE".equalsIgnoreCase(t.value())) {
            return parseCloseCursor();
        }

        if (t.type() == TokenType.KEYWORD && "RAISE".equalsIgnoreCase(t.value())) {
            return parseRaiseStatement();
        }

        if (t.type() == TokenType.KEYWORD && "RETURN".equalsIgnoreCase(t.value())) {
            advance();
            AstNode ret = new AstNode("RETURN_STATEMENT");
            if (peek().type() != TokenType.SEMICOLON) {
                ret.addChild(parseExpressionNode());
            }
            consumeOptionalSemicolonFollowingStatement();
            return ret;
        }

        if (t.type() == TokenType.KEYWORD && "SET".equalsIgnoreCase(t.value())) {
            advance();
            if (isKeyword("TRANSACTION")) {
                return parseRawStatementUntilEndOrSemi(contextMode);
            }
            AstNode setStmt = new AstNode("SET_STATEMENT");
            setStmt.addChild(new AstNode("TARGET", consumeIdentifierOrKeywordValue()));
            consume(TokenType.EQUALS);
            setStmt.addChild(parseExpressionNode());
            consume(TokenType.SEMICOLON);
            return setStmt;
        }

        if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.KEYWORD) {
            Token next = peekAhead(1);
            if (next != null && next.type() == TokenType.OPERATOR && ":=".equals(next.value())) {
                String targetName = advance().value();
                advance(); // :=
                AstNode assign = new AstNode("ASSIGNMENT_STATEMENT");
                assign.addChild(new AstNode("TARGET", targetName));
                assign.addChild(parseExpressionNode());
                consume(TokenType.SEMICOLON);
                return assign;
            }
        }

        return parseRawStatementUntilEndOrSemi(contextMode);
    }

    public AstNode parseIfStatement() {
        AstNode ifStmt = new AstNode("IF_STATEMENT");
        consumeKeyword("IF");
        AstNode cond = parseExpressionUntilKeyword("THEN");
        ifStmt.addChild(wrapCondition(cond));
        consumeKeyword("THEN");
        ifStmt.addChild(parseStatementList(StatementListMode.IF_BRANCH));

        while (isKeyword("ELSIF") || isKeyword("ELSEIF")) {
            advance();
            AstNode elsif = new AstNode("ELSIF_BRANCH");
            elsif.addChild(wrapCondition(parseExpressionUntilKeyword("THEN")));
            consumeKeyword("THEN");
            elsif.addChild(parseStatementList(StatementListMode.IF_BRANCH));
            ifStmt.addChild(elsif);
        }

        if (isKeyword("ELSE")) {
            advance();
            AstNode elseBlk = new AstNode("ELSE_BLOCK");
            elseBlk.addChild(parseStatementList(StatementListMode.IF_BRANCH));
            ifStmt.addChild(elseBlk);
        }

        consumeKeyword("END");
        consumeKeyword("IF");
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return ifStmt;
    }

    /** Wraps an expression AST as {@code CONDITION} for semantic analysis. */
    protected static AstNode wrapCondition(AstNode expressionNode) {
        AstNode c = new AstNode("CONDITION");
        c.addChild(expressionNode);
        return c;
    }

    public AstNode parseWhileLoop() {
        AstNode wh = new AstNode("WHILE_STATEMENT");
        consumeKeyword("WHILE");
        AstNode cond = parseExpressionUntilWhileSuffix();
        wh.addChild(wrapCondition(cond));
        if (isKeyword("DO")) {
            advance();
            AstNode body = parseStatementList(StatementListMode.WHILE_DO);
            wh.addChild(body);
            consumeKeyword("END");
            consumeKeyword("WHILE");
        } else if (isKeyword("LOOP")) {
            advance();
            AstNode body = parseStatementList(StatementListMode.LOOP_BODY);
            wh.addChild(body);
            consumeKeyword("END");
            consumeKeyword("LOOP");
        } else {
            throw new SqlParser.ParseException(String.format(
                    "Expected DO or LOOP after WHILE condition at line %d, column %d",
                    peek().line(), peek().column()));
        }
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return wh;
    }

    /** Reads condition until {@code DO} or {@code LOOP} at paren depth 0. */
    private AstNode parseExpressionUntilWhileSuffix() {
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (paren == 0 && t.type() == TokenType.KEYWORD
                    && ("DO".equalsIgnoreCase(t.value()) || "LOOP".equalsIgnoreCase(t.value()))) {
                break;
            }
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
        return new AstNode("EXPRESSION", sb.toString().trim());
    }

    public AstNode parseForLoop() {
        AstNode fo = new AstNode("FOR_STATEMENT");
        consumeKeyword("FOR");
        String iter = consumeIdentifierOrKeywordValue();
        fo.addChild(new AstNode("ITERATOR", iter));
        consumeKeyword("IN");

        if (peek().type() == TokenType.LEFT_PAREN) {
            AstNode sub = parseParenthesizedSource();
            AstNode cursor = new AstNode("FOR_CURSOR");
            cursor.addChild(sub);
            fo.addChild(cursor);
        } else if (lookaheadDoubleDotBeforeLoop()) {
            AstNode low = parseExpressionUntilDoubleDot();
            consumeDoubleDot();
            AstNode high = parseExpressionUntilKeyword("LOOP");
            AstNode range = new AstNode("FOR_RANGE");
            range.addChild(low);
            range.addChild(high);
            fo.addChild(range);
        } else {
            AstNode low = parseExpressionUntilKeyword("LOOP");
            AstNode cursor = new AstNode("FOR_CURSOR");
            cursor.addChild(low);
            fo.addChild(cursor);
        }

        consumeKeyword("LOOP");
        fo.addChild(parseStatementList(StatementListMode.LOOP_BODY));
        consumeKeyword("END");
        consumeKeyword("LOOP");
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return fo;
    }

    public AstNode parseLoop() {
        AstNode lp = new AstNode("LOOP_STATEMENT");
        consumeKeyword("LOOP");
        lp.addChild(parseStatementList(StatementListMode.LOOP_BODY));
        consumeKeyword("END");
        consumeKeyword("LOOP");
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return lp;
    }

    public AstNode parseCaseStatement() {
        consumeKeyword("CASE");
        AstNode cas;
        if (isKeyword("WHEN")) {
            cas = new AstNode("CASE_STATEMENT", "SEARCHED");
            while (isKeyword("WHEN")) {
                advance();
                AstNode branch = new AstNode("CASE_BRANCH");
                branch.addChild(wrapCondition(parseExpressionUntilKeyword("THEN")));
                consumeKeyword("THEN");
                branch.addChild(parseStatementList(StatementListMode.CASE_BRANCH));
                cas.addChild(branch);
            }
        } else {
            cas = new AstNode("CASE_STATEMENT", "SIMPLE");
            AstNode disc = new AstNode("CASE_DISCRIMINANT");
            disc.addChild(parseExpressionUntilKeyword("WHEN"));
            cas.addChild(disc);
            while (isKeyword("WHEN")) {
                advance();
                AstNode branch = new AstNode("CASE_BRANCH");
                branch.addChild(wrapCondition(parseExpressionUntilKeyword("THEN")));
                consumeKeyword("THEN");
                branch.addChild(parseStatementList(StatementListMode.CASE_BRANCH));
                cas.addChild(branch);
            }
        }

        if (isKeyword("ELSE")) {
            advance();
            AstNode elseBlk = new AstNode("ELSE_BLOCK");
            elseBlk.addChild(parseStatementList(StatementListMode.CASE_ELSE));
            cas.addChild(elseBlk);
        }

        consumeKeyword("END");
        consumeKeyword("CASE");
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return cas;
    }

    public AstNode parseExitWhen() {
        AstNode ex = new AstNode("EXIT_WHEN_STATEMENT");
        consumeKeyword("EXIT");
        consumeKeyword("WHEN");
        ex.addChild(wrapCondition(parseExpressionNode()));
        consume(TokenType.SEMICOLON);
        return ex;
    }

    private AstNode parseExpressionUntilKeyword(String kw) {
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (paren == 0 && t.type() == TokenType.KEYWORD && kw.equalsIgnoreCase(t.value())) {
                break;
            }
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
        String s = sb.toString().trim();
        if (s.isEmpty()) {
            throw new SqlParser.ParseException(String.format(
                    "Expected expression before '%s' at line %d, column %d",
                    kw, peek().line(), peek().column()));
        }
        return new AstNode("EXPRESSION", s);
    }

    private AstNode parseExpressionUntilDoubleDot() {
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (paren == 0 && t.type() == TokenType.DOT && peekAhead(1) != null
                    && peekAhead(1).type() == TokenType.DOT) {
                break;
            }
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
        return new AstNode("EXPRESSION", sb.toString().trim());
    }

    private void consumeDoubleDot() {
        consume(TokenType.DOT);
        consume(TokenType.DOT);
    }

    /**
     * True when the slice from the current position to the enclosing {@code LOOP} contains
     * a range {@code ..} at paren depth 0 (PL/SQL integer/reverse ranges).
     */
    private boolean lookaheadDoubleDotBeforeLoop() {
        int paren = 0;
        for (int i = pos; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.type() == TokenType.EOF) {
                return false;
            }
            if (t.type() == TokenType.LEFT_PAREN) {
                paren++;
            } else if (t.type() == TokenType.RIGHT_PAREN) {
                paren--;
            }
            if (paren == 0 && t.type() == TokenType.KEYWORD && "LOOP".equalsIgnoreCase(t.value())) {
                return false;
            }
            if (paren == 0 && t.type() == TokenType.DOT && i + 1 < tokens.size()
                    && tokens.get(i + 1).type() == TokenType.DOT) {
                return true;
            }
        }
        return false;
    }

    /** Balanced parentheses including the outer {@code ( )}. */
    private AstNode parseParenthesizedSource() {
        StringBuilder sb = new StringBuilder();
        if (peek().type() != TokenType.LEFT_PAREN) {
            throw new SqlParser.ParseException("Expected '('");
        }
        int depth = 0;
        while (peek().type() != TokenType.EOF) {
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
            if (t.type() == TokenType.RIGHT_PAREN && depth == 0) {
                break;
            }
        }
        return new AstNode("EXPRESSION", sb.toString().trim());
    }

    private AstNode parseRawStatementUntilEndOrSemi(StatementListMode contextMode) {
        AstNode raw = new AstNode("RAW_STATEMENT");
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (paren == 0 && t.type() == TokenType.SEMICOLON) {
                advance();
                break;
            }
            if (paren == 0 && atStatementListTerminator(contextMode)) {
                break;
            }
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
        if (!sb.isEmpty()) {
            raw.addChild(new AstNode("EXPRESSION", sb.toString().trim()));
        }
        return raw;
    }

    protected AstNode parseExpressionNode() {
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (t.type() == TokenType.SEMICOLON && paren == 0) {
                break;
            }
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
        return new AstNode("EXPRESSION", sb.toString().trim());
    }

    // -------------------------------------------------------------------------
    // Token helpers
    // -------------------------------------------------------------------------

    protected Token peek() {
        return tokens.get(Math.min(pos, tokens.size() - 1));
    }

    protected Token peekAhead(int offset) {
        int idx = pos + offset;
        if (idx < 0 || idx >= tokens.size()) {
            return null;
        }
        return tokens.get(idx);
    }

    protected Token advance() {
        Token t = tokens.get(pos);
        if (t.type() != TokenType.EOF) {
            pos++;
        }
        return t;
    }

    protected void consume(TokenType type) {
        Token t = peek();
        if (t.type() != type) {
            throw new SqlParser.ParseException(String.format(
                    "Expected %s but found '%s' at line %d, column %d",
                    type, t.value(), t.line(), t.column()));
        }
        advance();
    }

    protected void consumeKeyword(String kw) {
        Token t = peek();
        if (t.type() != TokenType.KEYWORD || !t.value().equalsIgnoreCase(kw)) {
            throw new SqlParser.ParseException(String.format(
                    "Expected '%s' but found '%s' at line %d, column %d",
                    kw, t.value(), t.line(), t.column()));
        }
        advance();
    }

    protected boolean matchKeyword(String kw) {
        Token t = peek();
        return t.type() == TokenType.KEYWORD && t.value().equalsIgnoreCase(kw);
    }

    protected boolean isKeyword(String kw) {
        return matchKeyword(kw);
    }

    protected String consumeIdentifierOrKeywordValue() {
        Token t = peek();
        if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.KEYWORD) {
            return advance().value();
        }
        throw new SqlParser.ParseException(String.format(
                "Expected identifier at line %d, column %d",
                t.line(), t.column()));
    }
}
