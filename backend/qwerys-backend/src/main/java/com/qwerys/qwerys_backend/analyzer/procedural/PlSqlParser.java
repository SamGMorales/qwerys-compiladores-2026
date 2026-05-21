package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.Token;
import com.qwerys.qwerys_backend.analyzer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PL/SQL-specific procedural parsing (Oracle).
 *
 * <p>Overrides selection between {@code IS} vs {@code FOR} after {@code CURSOR name}, optional
 * {@code END label;} labels, and exposes an entry point for {@code CREATE … IS|AS} routine bodies.
 */
public class PlSqlParser extends ProceduralParser {

    public PlSqlParser(List<Token> tokens, SqlDialect dialect) {
        super(tokens, dialect);
    }

    @Override
    protected void consumeCursorSelectIntro() {
        if (matchKeyword("IS")) {
            advance();
        } else {
            consumeKeyword("FOR");
        }
    }

    @Override
    protected void consumeOptionalEndNameLabel() {
        Token t = peek();
        if (t.type() != TokenType.IDENTIFIER && t.type() != TokenType.KEYWORD) {
            return;
        }
        String u = t.value().toUpperCase(Locale.ROOT);
        if ("IF".equals(u) || "LOOP".equals(u) || "CASE".equals(u) || "WHILE".equals(u)
                || "TRY".equals(u) || "CATCH".equals(u)) {
            return;
        }
        advance();
    }

    /**
     * After {@code IS} / {@code AS} in {@code CREATE PROCEDURE|FUNCTION|PACKAGE BODY|TRIGGER} (header
     * already consumed by {@link com.qwerys.qwerys_backend.analyzer.SqlParser}).
     *
     * <p>Oracle named units may declare locals between {@code IS|AS} and {@code BEGIN} <em>without</em>
     * a leading {@code DECLARE} (unlike anonymous blocks).
     */
    public AstNode parseRoutineBodyAfterIsAs() {
        if (isKeyword("DECLARE")) {
            return parseBlockFromDeclare();
        }
        if (isKeyword("BEGIN")) {
            return parseBlockFromBegin();
        }
        AstNode root = new AstNode("BLOCK_STATEMENT");
        List<AstNode> decls = new ArrayList<>();
        while (!isKeyword("BEGIN")) {
            if (peek().type() == TokenType.EOF) {
                throw new SqlParser.ParseException(String.format(
                        "Expected BEGIN after declarations in Oracle routine body at line %d, column %d",
                        peek().line(), peek().column()));
            }
            if (leadsExecutableProceduralKeyword()) {
                throw new SqlParser.ParseException(String.format(
                        "Expected variable or cursor declaration or BEGIN after IS/AS at line %d, column %d",
                        peek().line(), peek().column()));
            }
            decls.add(parseVariableDeclaration());
        }
        if (!decls.isEmpty()) {
            AstNode declareSection = new AstNode("DECLARE_SECTION");
            for (AstNode d : decls) {
                declareSection.addChild(d);
            }
            root.addChild(declareSection);
        }
        consumeKeyword("BEGIN");
        parseBlockBodyAfterBegin(root);
        consumeOptionalTrailingSemicolon();
        return root;
    }

}
