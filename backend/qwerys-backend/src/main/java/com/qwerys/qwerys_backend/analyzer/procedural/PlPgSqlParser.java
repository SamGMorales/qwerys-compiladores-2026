package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlLexer;
import com.qwerys.qwerys_backend.analyzer.Token;
import com.qwerys.qwerys_backend.analyzer.TokenType;

import java.util.List;
import java.util.Locale;

/**
 * PL/pgSQL–specific procedural parsing (PostgreSQL): dollar-quoted bodies, {@code RETURN QUERY},
 * {@code EXECUTE}, {@code PERFORM}, {@code GET DIAGNOSTICS}, {@code NOTIFY}/{@code LISTEN},
 * and {@code :=} initializers in {@code DECLARE}.
 */
public class PlPgSqlParser extends ProceduralParser {

    public PlPgSqlParser(List<Token> tokens, SqlDialect dialect) {
        super(tokens, dialect);
    }

    /**
     * Strips {@code $$…$$} or {@code $tag$…$tag$} delimiters and tokenizes the inner PL/pgSQL only.
     */
    public static String stripDollarQuotes(String literal) {
        if (literal == null || literal.length() < 2 || literal.charAt(0) != '$') {
            return literal == null ? "" : literal;
        }
        int second = literal.indexOf('$', 1);
        if (second < 0) {
            return literal;
        }
        String tag = literal.substring(1, second);
        String closing = "$" + tag + "$";
        if (literal.length() < second + 1 + closing.length()
                || !literal.endsWith(closing)) {
            return literal;
        }
        return literal.substring(second + 1, literal.length() - closing.length());
    }

    /**
     * Parses the inside of a dollar-quoted (or single-quoted) routine / {@code DO} body.
     */
    public static AstNode parseInnerBody(String literal, SqlDialect dialect) {
        String inner = literal;
        if (literal != null && literal.startsWith("$") && literal.indexOf('$', 1) > 0) {
            inner = stripDollarQuotes(literal);
        } else if (literal != null && literal.length() >= 2
                && literal.startsWith("'") && literal.endsWith("'")) {
            inner = literal.substring(1, literal.length() - 1).replace("''", "'");
        }
        List<Token> innerTokens = new SqlLexer(inner, dialect).tokenize();
        PlPgSqlParser p = new PlPgSqlParser(innerTokens, dialect);
        if (p.isKeyword("DECLARE")) {
            return p.parseBlockFromDeclare();
        }
        if (p.isKeyword("BEGIN")) {
            return p.parseBlockFromBegin();
        }
        AstNode root = new AstNode("BLOCK_STATEMENT");
        root.addChild(p.parseScriptFragments());
        return root;
    }

    /** {@code DECLARE} section allows {@code nombre tipo := expr;}. */
    @Override
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
        if (isKeyword("DEFAULT") || (peek().type() == TokenType.OPERATOR && ":=".equals(peek().value()))) {
            if (isKeyword("DEFAULT")) {
                advance();
            } else {
                advance();
            }
            AstNode def = new AstNode("DEFAULT_VALUE");
            def.addChild(parseExpressionNode());
            decl.addChild(def);
        }
        consume(TokenType.SEMICOLON);
        return decl;
    }

    @Override
    protected boolean leadsExecutableProceduralKeyword() {
        Token t = peek();
        if (t.type() == TokenType.KEYWORD) {
            String u = t.value().toUpperCase(Locale.ROOT);
            if ("RETURN".equals(u) || "EXECUTE".equals(u) || "PERFORM".equals(u)
                    || "GET".equals(u) || "NOTIFY".equals(u) || "LISTEN".equals(u)) {
                return true;
            }
        }
        return super.leadsExecutableProceduralKeyword();
    }

    @Override
    protected AstNode tryParseDialectProceduralStatement(StatementListMode contextMode) {
        if (peek().type() != TokenType.KEYWORD) {
            return null;
        }
        return switch (peek().value().toUpperCase(Locale.ROOT)) {
            case "RETURN" -> parseReturnStatement();
            case "EXECUTE" -> parseExecuteStatement();
            case "PERFORM" -> parsePerformStatement();
            case "GET" -> parseGetDiagnosticsStatement();
            case "NOTIFY" -> parseNotifyStatement();
            case "LISTEN" -> parseListenStatement();
            default -> null;
        };
    }

    @Override
    public AstNode parseRaiseStatement() {
        consumeKeyword("RAISE");
        AstNode stmt = new AstNode("RAISE_STATEMENT");
        Token ahead = peek();
        if (ahead.type() == TokenType.KEYWORD) {
            String k = ahead.value().toUpperCase(Locale.ROOT);
            if ("NOTICE".equals(k) || "WARNING".equals(k) || "EXCEPTION".equals(k) || "DEBUG".equals(k)) {
                stmt.addChild(new AstNode("RAISE_KIND", advance().value()));
                ahead = peek();
            }
        }
        if (ahead.type() == TokenType.STRING || ahead.type() == TokenType.IDENTIFIER) {
            stmt.addChild(new AstNode("MESSAGE", advance().value()));
        } else if (ahead.type() == TokenType.KEYWORD) {
            stmt.addChild(new AstNode("MESSAGE", advance().value()));
        }
        consumeOptionalTrailingSemicolon();
        return stmt;
    }

    public AstNode parseReturnStatement() {
        consumeKeyword("RETURN");
        if (matchKeyword("QUERY")) {
            advance();
            AstNode stmt = new AstNode("RETURN_QUERY_STATEMENT");
            stmt.addChild(parseExpressionNode());
            consume(TokenType.SEMICOLON);
            return stmt;
        }
        if (matchKeyword("NEXT")) {
            advance();
            AstNode stmt = new AstNode("RETURN_NEXT_STATEMENT");
            if (peek().type() != TokenType.SEMICOLON) {
                stmt.addChild(parseExpressionNode());
            }
            consume(TokenType.SEMICOLON);
            return stmt;
        }
        if (peek().type() == TokenType.SEMICOLON) {
            AstNode stmt = new AstNode("RETURN_STATEMENT", "");
            advance();
            return stmt;
        }
        AstNode stmt = new AstNode("RETURN_STATEMENT");
        stmt.addChild(parseExpressionNode());
        consume(TokenType.SEMICOLON);
        return stmt;
    }

    private AstNode parseExecuteStatement() {
        consumeKeyword("EXECUTE");
        AstNode stmt = new AstNode("EXECUTE_STATEMENT");
        stmt.addChild(new AstNode("EXPRESSION", consumeUntilSemicolonTrimmed()));
        consumeOptionalTrailingSemicolon();
        return stmt;
    }

    private AstNode parsePerformStatement() {
        consumeKeyword("PERFORM");
        AstNode stmt = new AstNode("PERFORM_STATEMENT");
        stmt.addChild(new AstNode("EXPRESSION", consumeUntilSemicolonTrimmed()));
        consumeOptionalTrailingSemicolon();
        return stmt;
    }

    private AstNode parseGetDiagnosticsStatement() {
        consumeKeyword("GET");
        consumeKeyword("DIAGNOSTICS");
        AstNode stmt = new AstNode("GET_DIAGNOSTICS_STATEMENT");
        stmt.addChild(new AstNode("EXPRESSION", consumeUntilSemicolonTrimmed()));
        consume(TokenType.SEMICOLON);
        return stmt;
    }

    private AstNode parseNotifyStatement() {
        consumeKeyword("NOTIFY");
        AstNode stmt = new AstNode("NOTIFY_STATEMENT");
        stmt.addChild(new AstNode("EXPRESSION", consumeUntilSemicolonTrimmed()));
        consumeOptionalTrailingSemicolon();
        return stmt;
    }

    private AstNode parseListenStatement() {
        consumeKeyword("LISTEN");
        AstNode stmt = new AstNode("LISTEN_STATEMENT");
        stmt.addChild(new AstNode("EXPRESSION", consumeUntilSemicolonTrimmed()));
        consumeOptionalTrailingSemicolon();
        return stmt;
    }

    private String consumeUntilSemicolonTrimmed() {
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
        return sb.toString().trim();
    }
}
