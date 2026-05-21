package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCommentHandlingTest {

    @Test
    void mysqlHashLineCommentIsSkippedInLexer() {
        List<Token> tokens = new SqlLexer("SELECT 1 # trailing\nFROM t", SqlDialect.MYSQL).tokenize();
        List<String> values = tokens.stream().filter(t -> t.type() != TokenType.EOF).map(Token::value).toList();
        assertEquals(List.of("SELECT", "1", "FROM", "t"), values);
    }

    @Test
    void postgresHashIsNotLineComment() {
        List<Token> tokens = new SqlLexer("SELECT 1 # 2", SqlDialect.POSTGRESQL).tokenize();
        List<String> values = tokens.stream().filter(t -> t.type() != TokenType.EOF).map(Token::value).toList();
        assertTrue(values.contains("#"), "PostgreSQL should keep # for XOR / operators");
    }

    @Test
    void postgresDollarQuotedBodyDoesNotTokenizeInnerSql() {
        List<Token> tokens = new SqlLexer(
                "SELECT $$ DROP TABLE users; -- fake $$::text AS body", SqlDialect.POSTGRESQL).tokenize();
        long stringLiterals = tokens.stream().filter(t -> t.type() == TokenType.STRING).count();
        assertEquals(1, stringLiterals);
        assertTrue(tokens.stream().noneMatch(t -> "DROP".equalsIgnoreCase(t.value())));
    }

    @Test
    void statementSplitterMysqlHonorsHashCommentsForSemicolons() {
        String raw = "SELECT 1; # not a split; here\nSELECT 2";
        List<String> mysql = StatementSplitter.split(raw, SqlDialect.MYSQL);
        assertEquals(2, mysql.size());
        assertTrue(mysql.get(0).contains("SELECT 1"));
        assertTrue(mysql.get(1).contains("SELECT 2"));
        // Comment text stays in the fragment (like --), but lexer skips # lines for MYSQL
        List<String> secondValues = new SqlLexer(mysql.get(1), SqlDialect.MYSQL).tokenize().stream()
                .filter(t -> t.type() != TokenType.EOF)
                .map(Token::value)
                .toList();
        assertEquals(List.of("SELECT", "2"), secondValues);

        List<String> pg = StatementSplitter.split(raw, SqlDialect.POSTGRESQL);
        assertTrue(pg.size() > mysql.size(),
                "PostgreSQL does not treat # as a line comment, so the semicolon in '# ...;...' splits statements");
    }

    @Test
    void mysqlVersionedBlockCommentEmbedsInnerSqlInLexer() {
        List<Token> tokens = new SqlLexer(
                "SELECT 1 /*!40101 STRAIGHT_JOIN */ FROM t", SqlDialect.MYSQL).tokenize();
        List<String> values = tokens.stream().filter(t -> t.type() != TokenType.EOF).map(Token::value).toList();
        assertTrue(values.contains("STRAIGHT_JOIN"));
        assertEquals(List.of("SELECT", "1", "STRAIGHT_JOIN", "FROM", "t"), values);
    }

    @Test
    void mysqlPlainBlockCommentStillSkipped() {
        List<Token> tokens = new SqlLexer("SELECT 1 /* hide */ FROM t", SqlDialect.MYSQL).tokenize();
        List<String> values = tokens.stream().filter(t -> t.type() != TokenType.EOF).map(Token::value).toList();
        assertEquals(List.of("SELECT", "1", "FROM", "t"), values);
    }

    @Test
    void postgresqlTreatsBangBlockAsOrdinaryComment() {
        List<Token> tokens = new SqlLexer("SELECT 1 /*! SELECT 999 */ FROM t", SqlDialect.POSTGRESQL).tokenize();
        List<String> values = tokens.stream().filter(t -> t.type() != TokenType.EOF).map(Token::value).toList();
        assertEquals(List.of("SELECT", "1", "FROM", "t"), values);
        assertTrue(tokens.stream().noneMatch(t -> "999".equals(t.value())));
    }

    @Test
    void statementSplitterMysqlExpandsVersionedCommentForSemicolons() {
        String raw = "/*! SELECT 1 */;/*! SELECT 2 */";
        List<String> parts = StatementSplitter.split(raw, SqlDialect.MYSQL);
        assertEquals(2, parts.size());
        List<String> t0 = new SqlLexer(parts.get(0), SqlDialect.MYSQL).tokenize().stream()
                .filter(t -> t.type() != TokenType.EOF).map(Token::value).toList();
        assertEquals(List.of("SELECT", "1"), t0);
        List<String> t1 = new SqlLexer(parts.get(1), SqlDialect.MYSQL).tokenize().stream()
                .filter(t -> t.type() != TokenType.EOF).map(Token::value).toList();
        assertEquals(List.of("SELECT", "2"), t1);
    }

    @Test
    void mysqlExecutableCommentIgnoresStarSlashInsideString() {
        List<Token> tokens = new SqlLexer(
                "SELECT /*! 'x*/y' FROM */ t", SqlDialect.MYSQL).tokenize();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.STRING && t.value().contains("*/")));
    }
}
