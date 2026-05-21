package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.Token;
import com.qwerys.qwerys_backend.analyzer.TokenType;

import java.util.List;
import java.util.Locale;

/**
 * MySQL stored-program (SQL/PSM) procedural parsing: {@code REPEAT}/{@code LEAVE}/{@code ITERATE},
 * labeled {@code LOOP}, {@code SIGNAL}/{@code RESIGNAL}, function {@code RETURN}, and optional
 * routine labels after {@code END LOOP}.
 */
public class MySqlPsmParser extends ProceduralParser {

    public MySqlPsmParser(List<Token> tokens, SqlDialect dialect) {
        super(tokens, dialect);
    }

    @Override
    protected void prependInnerDeclareSection(AstNode root) {
        if (!isKeyword("DECLARE")) {
            return;
        }
        AstNode declareSection = new AstNode("DECLARE_SECTION");
        while (isKeyword("DECLARE")) {
            consumeKeyword("DECLARE");
            if (isKeyword("CONTINUE") || isKeyword("EXIT")) {
                declareSection.addChild(parseExitContinueHandlerDeclaration());
            } else {
                declareSection.addChild(parseVariableDeclaration());
            }
        }
        root.addChild(declareSection);
    }

    @Override
    protected boolean leadsExecutableProceduralKeyword() {
        Token t = peek();
        if (t.type() == TokenType.KEYWORD) {
            String u = t.value().toUpperCase(Locale.ROOT);
            if ("REPEAT".equals(u) || "LEAVE".equals(u) || "ITERATE".equals(u)
                    || "SIGNAL".equals(u) || "RESIGNAL".equals(u) || "RETURN".equals(u)) {
                return true;
            }
        }
        return super.leadsExecutableProceduralKeyword();
    }

    @Override
    protected AstNode tryParseDialectProceduralStatement(StatementListMode contextMode) {
        Token t = peek();
        if (t.type() == TokenType.KEYWORD) {
            return switch (t.value().toUpperCase(Locale.ROOT)) {
                case "REPEAT" -> parseRepeatStatement();
                case "LEAVE" -> parseLeaveStatement();
                case "ITERATE" -> parseIterateStatement();
                case "SIGNAL" -> parseSignalStatement();
                case "RESIGNAL" -> parseResignalStatement();
                case "RETURN" -> parseReturnStatement();
                default -> tryParseLabeledLoop();
            };
        }
        if (t.type() == TokenType.IDENTIFIER || t.type() == TokenType.KEYWORD) {
            return tryParseLabeledLoop();
        }
        return null;
    }

    /**
     * {@code label: LOOP ... END LOOP [label];}
     */
    private AstNode tryParseLabeledLoop() {
        Token t0 = peek();
        if (t0.type() != TokenType.IDENTIFIER && t0.type() != TokenType.KEYWORD) {
            return null;
        }
        Token t1 = peekAhead(1);
        if (t1 == null || t1.type() != TokenType.COLON) {
            return null;
        }
        Token t2 = peekAhead(2);
        if (t2 == null || t2.type() != TokenType.KEYWORD
                || !"LOOP".equalsIgnoreCase(t2.value())) {
            return null;
        }
        String label = advance().value();
        advance(); // :
        AstNode lp = new AstNode("LOOP_STATEMENT", label);
        consumeKeyword("LOOP");
        lp.addChild(parseStatementList(StatementListMode.LOOP_BODY));
        consumeKeyword("END");
        consumeKeyword("LOOP");
        if (peek().type() == TokenType.IDENTIFIER || peek().type() == TokenType.KEYWORD) {
            advance();
        }
        consumeOptionalTrailingSemicolon();
        return lp;
    }

    /** {@code REPEAT ... UNTIL expr END REPEAT} */
    public AstNode parseRepeatStatement() {
        consumeKeyword("REPEAT");
        AstNode rep = new AstNode("REPEAT_STATEMENT");
        rep.addChild(parseStatementList(StatementListMode.REPEAT_BODY));
        consumeKeyword("UNTIL");
        rep.addChild(wrapCondition(parseExpressionUntilEndRepeat()));
        consumeKeyword("END");
        consumeKeyword("REPEAT");
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return rep;
    }

    private AstNode parseExpressionUntilEndRepeat() {
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (paren == 0 && isKeyword("END") && isPeekAheadKeyword(1, "REPEAT")) {
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
                    "Expected UNTIL condition before END REPEAT at line %d, column %d",
                    peek().line(), peek().column()));
        }
        return new AstNode("EXPRESSION", s);
    }

    private AstNode parseLeaveStatement() {
        consumeKeyword("LEAVE");
        String lbl = "";
        if (peek().type() != TokenType.SEMICOLON && peek().type() != TokenType.EOF) {
            lbl = consumeIdentifierOrKeywordValue();
        }
        AstNode n = new AstNode("LEAVE_STATEMENT", lbl.isEmpty() ? null : lbl);
        consume(TokenType.SEMICOLON);
        return n;
    }

    private AstNode parseIterateStatement() {
        consumeKeyword("ITERATE");
        String lbl = consumeIdentifierOrKeywordValue();
        AstNode n = new AstNode("ITERATE_STATEMENT", lbl);
        consume(TokenType.SEMICOLON);
        return n;
    }

    /** Function body {@code RETURN [expr];} */
    public AstNode parseReturnStatement() {
        consumeKeyword("RETURN");
        AstNode r = new AstNode("RETURN_STATEMENT");
        if (peek().type() != TokenType.SEMICOLON) {
            r.addChild(parseExpressionNode());
        }
        consume(TokenType.SEMICOLON);
        return r;
    }

    /**
     * Best-effort {@code SIGNAL} / {@code SIGNAL SQLSTATE '...'} / {@code SIGNAL condition_name}
     * with optional {@code SET} clause until {@code ;}.
     */
    private AstNode parseSignalStatement() {
        consumeKeyword("SIGNAL");
        AstNode s = new AstNode("SIGNAL_STATEMENT");
        StringBuilder detail = new StringBuilder();
        while (peek().type() != TokenType.EOF && peek().type() != TokenType.SEMICOLON) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append(advance().value());
        }
        String rest = detail.toString().trim();
        if (!rest.isEmpty()) {
            s.addChild(new AstNode("SIGNAL_DETAIL", rest));
        }
        consume(TokenType.SEMICOLON);
        return s;
    }

    private AstNode parseResignalStatement() {
        consumeKeyword("RESIGNAL");
        AstNode s = new AstNode("RESIGNAL_STATEMENT");
        StringBuilder detail = new StringBuilder();
        while (peek().type() != TokenType.EOF && peek().type() != TokenType.SEMICOLON) {
            if (detail.length() > 0) {
                detail.append(' ');
            }
            detail.append(advance().value());
        }
        if (detail.length() > 0) {
            s.addChild(new AstNode("RESIGNAL_DETAIL", detail.toString().trim()));
        }
        consume(TokenType.SEMICOLON);
        return s;
    }

    @Override
    protected void consumeOptionalEndNameLabel() {
        Token t = peek();
        if (t.type() != TokenType.IDENTIFIER && t.type() != TokenType.KEYWORD) {
            return;
        }
        String u = t.value().toUpperCase(Locale.ROOT);
        if ("IF".equals(u) || "LOOP".equals(u) || "CASE".equals(u) || "WHILE".equals(u)
                || "REPEAT".equals(u)) {
            return;
        }
        advance();
    }
}
