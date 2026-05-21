package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.Token;
import com.qwerys.qwerys_backend.analyzer.TokenType;

import java.util.List;
import java.util.Locale;

/**
 * T-SQL–specific procedural parsing (SQL Server): {@code @} variables, {@code FETCH NEXT FROM},
 * {@code DEALLOCATE}, {@code THROW}, {@code GOTO}, {@code WAITFOR}, {@code CREATE OR ALTER} routine bodies.
 */
public class TSqlParser extends ProceduralParser {

    public TSqlParser(List<Token> tokens, SqlDialect dialect) {
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
            parseTsqlCommaSeparatedDeclarationsInto(declareSection);
        }
        root.addChild(declareSection);
    }

    /** {@code DECLARE @a INT [, @b INT ...] ;} — includes {@code DECLARE name CURSOR FOR SELECT … ;}. */
    private void parseTsqlCommaSeparatedDeclarationsInto(AstNode declareSection) {
        while (true) {
            String declName = consumeIdentifierOrKeywordValue();
            AstNode decl;
            if (isKeyword("CURSOR")) {
                consumeKeyword("CURSOR");
                // Whole SELECT through ';' — generic parseTypeNode() stops at '=' in WHERE (PROC bug).
                decl = finishCursorDeclaration(declName);
            } else {
                decl = new AstNode("VARIABLE_DECLARATION", declName);
                decl.addChild(parseTypeNode());
                if (isKeyword("DEFAULT")) {
                    advance();
                    AstNode def = new AstNode("DEFAULT_VALUE");
                    def.addChild(parseExpressionNode());
                    decl.addChild(def);
                }
            }
            declareSection.addChild(decl);
            if (peek().type() == TokenType.COMMA) {
                advance();
                continue;
            }
            if (!"CURSOR_DECLARATION".equals(decl.getNodeType())) {
                consume(TokenType.SEMICOLON);
            }
            break;
        }
    }

    @Override
    public AstNode parseIfStatement() {
        AstNode ifStmt = new AstNode("IF_STATEMENT");
        consumeKeyword("IF");
        ifStmt.addChild(wrapCondition(parseTsqlIfCondition()));
        AstNode thenList = new AstNode("STATEMENT_LIST");
        thenList.addChild(parseTsqlIfBranchStatement());
        ifStmt.addChild(thenList);
        if (isKeyword("ELSE")) {
            advance();
            AstNode elseBlk = new AstNode("ELSE_BLOCK");
            AstNode elseList = new AstNode("STATEMENT_LIST");
            elseList.addChild(parseTsqlIfBranchStatement());
            elseBlk.addChild(elseList);
            ifStmt.addChild(elseBlk);
        }
        return ifStmt;
    }

    private AstNode parseTsqlIfBranchStatement() {
        if (isKeyword("BEGIN")) {
            return parseNestedBlockStatement();
        }
        return parseStatementInBlock(StatementListMode.IF_BRANCH);
    }

    private AstNode parseTsqlIfCondition() {
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (paren == 0 && tsqlIfBodyStartsHere(t)) {
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
                    "Expected boolean expression after IF at line %d, column %d",
                    peek().line(), peek().column()));
        }
        return new AstNode("EXPRESSION", s);
    }

    private boolean tsqlIfBodyStartsHere(Token t) {
        if (t.type() != TokenType.KEYWORD && t.type() != TokenType.IDENTIFIER) {
            return false;
        }
        String u = t.value().toUpperCase(Locale.ROOT);
        return switch (u) {
            case "BEGIN", "RETURN", "SET", "SELECT", "INSERT", "UPDATE", "DELETE", "WITH",
                    "EXEC", "EXECUTE", "IF", "WHILE", "BREAK", "CONTINUE", "THROW", "GOTO",
                    "FETCH", "OPEN", "CLOSE", "DEALLOCATE", "WAITFOR", "RAISERROR", "PRINT",
                    "MERGE" -> true;
            default -> false;
        };
    }

    /**
     * After {@code AS} in {@code CREATE PROCEDURE|FUNCTION|TRIGGER}; T-SQL allows a flat script without
     * outer {@code BEGIN…END}.
     */
    public AstNode parseRoutineBodyAfterAs() {
        if (isKeyword("BEGIN")) {
            return parseBlockFromBegin();
        }
        if (isKeyword("DECLARE")) {
            return parseBlockFromDeclare();
        }
        AstNode root = new AstNode("BLOCK_STATEMENT");
        root.addChild(parseScriptFragments());
        return root;
    }

    @Override
    protected boolean leadsExecutableProceduralKeyword() {
        Token t = peek();
        if (t.type() == TokenType.KEYWORD) {
            String u = t.value().toUpperCase(Locale.ROOT);
            if (u.equals("DEALLOCATE")
                    || u.equals("THROW")
                    || u.equals("GOTO")
                    || u.equals("WAITFOR")) {
                return true;
            }
        }
        return super.leadsExecutableProceduralKeyword();
    }

    @Override
    protected AstNode tryParseDialectProceduralStatement(StatementListMode contextMode) {
        if (peek().type() != TokenType.KEYWORD && peek().type() != TokenType.IDENTIFIER) {
            return null;
        }
        if (peek().type() == TokenType.KEYWORD) {
            return switch (peek().value().toUpperCase(Locale.ROOT)) {
                case "DEALLOCATE" -> parseDeallocateStatement();
                case "THROW" -> parseThrowStatementNode();
                case "GOTO" -> parseGotoStatement();
                case "WAITFOR" -> parseWaitforStatement();
                default -> null;
            };
        }
        return null;
    }

    /**
     * {@code FETCH [NEXT] FROM cursor INTO @v ...;} — also accepts ISO {@code FETCH cursor INTO …}.
     */
    @Override
    public AstNode parseFetchCursor() {
        consumeKeyword("FETCH");
        if (matchKeyword("NEXT")) {
            advance();
        }
        if (matchKeyword("FROM")) {
            advance();
        }
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

    public AstNode parseThrowStatementNode() {
        consumeKeyword("THROW");
        AstNode stmt = new AstNode("THROW_STATEMENT");
        stmt.addChild(new AstNode("EXPRESSION", consumeUntilSemicolonTrimmed()));
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return stmt;
    }

    public AstNode parseDeallocateStatement() {
        consumeKeyword("DEALLOCATE");
        String name = consumeCursorNameForDeallocate();
        AstNode n = new AstNode("DEALLOCATE_CURSOR_STATEMENT", name);
        consumeOptionalTrailingSemicolon();
        return n;
    }

    public AstNode parseGotoStatement() {
        consumeKeyword("GOTO");
        String label = consumeIdentifierOrKeywordValue();
        AstNode n = new AstNode("GOTO_STATEMENT", label);
        consumeOptionalTrailingSemicolon();
        return n;
    }

    public AstNode parseWaitforStatement() {
        consumeKeyword("WAITFOR");
        AstNode n = new AstNode("WAITFOR_STATEMENT");
        n.addChild(new AstNode("EXPRESSION", consumeUntilSemicolonTrimmed()));
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return n;
    }

    private String consumeCursorNameForDeallocate() {
        Token t = peek();
        if (t.type() == TokenType.IDENTIFIER && t.value().startsWith("@")) {
            return advance().value();
        }
        return consumeIdentifierOrKeywordValue();
    }

    /**
     * T-SQL {@code WHILE expr BEGIN … END} — generic {@link ProceduralParser#parseWhileLoop()} expects
     * {@code DO}/{@code LOOP} after the condition (MySQL/PL-SQL shapes).
     */
    @Override
    public AstNode parseWhileLoop() {
        AstNode wh = new AstNode("WHILE_STATEMENT");
        consumeKeyword("WHILE");
        AstNode cond = parseExpressionUntilTsqlWhileSuffix();
        wh.addChild(wrapCondition(cond));
        if (isKeyword("BEGIN")) {
            consumeKeyword("BEGIN");
            AstNode body = parseStatementList(StatementListMode.WHILE_BEGIN_BODY);
            wh.addChild(body);
            consumeKeyword("END");
        } else if (isKeyword("DO")) {
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
                    "Expected BEGIN, DO, or LOOP after WHILE condition at line %d, column %d",
                    peek().line(), peek().column()));
        }
        if (peek().type() == TokenType.SEMICOLON) {
            advance();
        }
        return wh;
    }

    /** Reads WHILE condition until {@code BEGIN}, {@code DO}, or {@code LOOP} at paren depth 0. */
    private AstNode parseExpressionUntilTsqlWhileSuffix() {
        StringBuilder sb = new StringBuilder();
        int paren = 0;
        while (true) {
            Token t = peek();
            if (t.type() == TokenType.EOF) {
                break;
            }
            if (paren == 0 && t.type() == TokenType.KEYWORD) {
                String u = t.value().toUpperCase(Locale.ROOT);
                if ("BEGIN".equals(u) || "DO".equals(u) || "LOOP".equals(u)) {
                    break;
                }
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
                    "Expected condition after WHILE at line %d, column %d",
                    peek().line(), peek().column()));
        }
        return new AstNode("EXPRESSION", s);
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
