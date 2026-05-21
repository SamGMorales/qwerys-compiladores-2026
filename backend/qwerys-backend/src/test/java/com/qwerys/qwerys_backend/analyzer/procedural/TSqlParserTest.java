package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlLexer;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link TSqlParser} + {@link TSqlAnalyzer} codes PROC-SS-001 … 010 (incl. PROC-SS-008 @@IDENTITY). */
class TSqlParserTest {

    private static List<Token> lex(String sql) {
        return new SqlLexer(sql, SqlDialect.SQLSERVER).tokenize();
    }

    private static AstNode findDeep(AstNode n, String type) {
        if (n == null) {
            return null;
        }
        if (type.equals(n.getNodeType())) {
            return n;
        }
        for (AstNode c : n.getChildren()) {
            AstNode h = findDeep(c, type);
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    private static void assertHasCode(List<SemanticError> es, String code) {
        assertTrue(es.stream().anyMatch(e -> code.equals(e.code())),
                () -> "missing " + code + " in " + es.stream().map(SemanticError::code).toList());
    }

    @Test
    void parseBlock_beginWithDeclareInside() {
        String sql = "BEGIN\n"
                + "  DECLARE @x INT;\n"
                + "  SET @x = 1;\n"
                + "  SELECT @x;\n"
                + "END;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "DECLARE_SECTION"));
    }

    @Test
    void parseRoutineBodyAfterAs_flatScript() {
        String sql = "SELECT 1;";
        TSqlParser p = new TSqlParser(lex(sql), SqlDialect.SQLSERVER);
        AstNode ast = p.parseRoutineBodyAfterAs();
        assertEquals("BLOCK_STATEMENT", ast.getNodeType());
        assertNotNull(findDeep(ast, "STATEMENT_LIST"));
    }

    @Test
    void parseRoutineBodyAfterAs_nestedBegin() {
        String sql = "BEGIN\n  RETURN 0;\nEND;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseRoutineBodyAfterAs();
        assertNotNull(findDeep(ast, "RETURN_STATEMENT"));
    }

    @Test
    void ifStatement_withoutThenKeyword_branch() {
        String sql = "IF @a > 0\n"
                + "  SELECT 1;\n"
                + "ELSE\n"
                + "  SELECT 0;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseIfStatement();
        assertNotNull(findDeep(ast, "CONDITION"));
        assertNotNull(findDeep(ast, "ELSE_BLOCK"));
    }

    @Test
    void whileLoop_tsqlBeginEnd() {
        String sql = "WHILE @i > 0 BEGIN\n"
                + "  SET @i = @i - 1;\n"
                + "END;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseWhileLoop();
        assertNotNull(findDeep(ast, "WHILE_STATEMENT"));
    }

    @Test
    void tryCatch_parses() {
        String sql = "BEGIN\n"
                + "  BEGIN TRY\n"
                + "    SELECT 1;\n"
                + "  END TRY\n"
                + "  BEGIN CATCH\n"
                + "    THROW;\n"
                + "  END CATCH\n"
                + "END;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "EXCEPTION_BLOCK"));
    }

    @Test
    void throwStatementNode_parses() {
        String sql = "THROW 50000, 'err', 1;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseThrowStatementNode();
        assertEquals("THROW_STATEMENT", ast.getNodeType());
    }

    @Test
    void gotoStatement_parses() {
        String sql = "GOTO lbl;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseGotoStatement();
        assertEquals("GOTO_STATEMENT", ast.getNodeType());
    }

    @Test
    void waitforStatement_parses() {
        String sql = "WAITFOR DELAY '00:00:01';";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseWaitforStatement();
        assertEquals("WAITFOR_STATEMENT", ast.getNodeType());
    }

    @Test
    void fetchNextFromCursor_parses() {
        String sql = "FETCH NEXT FROM c INTO @a, @b;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseFetchCursor();
        assertEquals("FETCH_STATEMENT", ast.getNodeType());
    }

    @Test
    void deallocateCursor_parses() {
        String sql = "DEALLOCATE c;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseDeallocateStatement();
        assertEquals("DEALLOCATE_CURSOR_STATEMENT", ast.getNodeType());
    }

    @Test
    void declareCursorForSelect_parses() {
        String sql = "DECLARE @cur CURSOR FOR SELECT 1 AS x;";
        AstNode root = new AstNode("BLOCK_STATEMENT");
        new TSqlParser(lex(sql), SqlDialect.SQLSERVER).prependInnerDeclareSection(root);
        assertNotNull(findDeep(root, "CURSOR_DECLARATION"));
    }

    @Test
    void parseFailure_whileMissingBody() {
        String sql = "WHILE 1 = 1\nSELECT 1;";
        assertThrows(SqlParser.ParseException.class,
                () -> new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseWhileLoop());
    }

    @Test
    void nestedWhile_threeLevels() {
        String sql = "BEGIN\n"
                + "  WHILE 1=0 BEGIN\n"
                + "    WHILE 1=0 BEGIN\n"
                + "      WHILE 1=0 BEGIN\n"
                + "        SELECT 0;\n"
                + "      END\n"
                + "    END\n"
                + "  END\n"
                + "END;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseBlockFromBegin();
        long n = countNodes(ast, "WHILE_STATEMENT");
        assertTrue(n >= 3);
    }

    private static long countNodes(AstNode n, String type) {
        long c = type.equals(n.getNodeType()) ? 1 : 0;
        for (AstNode ch : n.getChildren()) {
            c += countNodes(ch, type);
        }
        return c;
    }

    @Test
    void fullCreateProcedure_sqlParser() {
        String sql = "CREATE PROCEDURE dbo.p AS\n"
                + "BEGIN\n"
                + "  SELECT 1;\n"
                + "END;";
        AstNode root = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertEquals("CREATE_PROCEDURE_STATEMENT", root.getNodeType());
    }

    @Test
    void procSs001_noNocount() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN SELECT 1; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-001");
    }

    @Test
    void procSs002_tryWithoutCatch() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN BEGIN TRY SELECT 1; END TRY END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-002");
    }

    @Test
    void procSs003_raiserrorLegacy() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN RAISERROR('x',16,1); END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-003");
    }

    @Test
    void procSs004_openWithoutDeallocate() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN DECLARE @v CURSOR FOR SELECT 1; OPEN c; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-004");
    }

    @Test
    void procSs005_tableVar_multiJoin() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN\n"
                + "SELECT * FROM @t a JOIN b ON 1=1 JOIN c ON 1=1 JOIN d ON 1=1;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-005");
    }

    @Test
    void procSs006_gotoPresent() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN GOTO x; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-006");
    }

    @Test
    void procSs007_selectStarIntoTemp() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN SELECT * INTO #t FROM u; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-007");
    }

    @Test
    void procSs008_identityWarning() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN SELECT @@IDENTITY; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-008");
    }

    @Test
    void procSs009_longWaitfor() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN WAITFOR DELAY '00:01:01'; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-009");
    }

    @Test
    void procSs010_sp_executesqlWithoutParams() {
        String sql = "CREATE PROCEDURE dbo.p AS BEGIN EXEC sp_executesql N'SELECT 1'; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        assertHasCode(new TSqlAnalyzer(ast, sql).analyze(), "PROC-SS-010");
    }

    @Test
    void stress_nestedBeginTry() {
        String sql = "BEGIN\n"
                + "  BEGIN TRY\n"
                + "    BEGIN TRY\n"
                + "      SELECT 1;\n"
                + "    END TRY\n"
                + "    BEGIN CATCH\n"
                + "      SELECT 2;\n"
                + "    END CATCH\n"
                + "  END TRY\n"
                + "  BEGIN CATCH\n"
                + "    SELECT 3;\n"
                + "  END CATCH\n"
                + "END;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseBlockFromBegin();
        long tries = countNodes(ast, "EXCEPTION_BLOCK");
        assertTrue(tries >= 1);
    }

    @Test
    void breakContinue_branchingKeywordsParseAsRawOrStatements() {
        String sql = "BEGIN\n  WHILE 1=0 BEGIN BREAK; CONTINUE; END;\nEND;";
        AstNode ast = new TSqlParser(lex(sql), SqlDialect.SQLSERVER).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "WHILE_STATEMENT"));
    }

    @Test
    void mergeAnalyzer_semanticAndTsql() {
        String sql = "CREATE PROCEDURE dbo.p AS\n"
                + "BEGIN\n"
                + "  DECLARE @x INT;\n"
                + "  SET @x = 1;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.SQLSERVER).parse();
        List<SemanticError> sem = ProceduralSemanticAnalyzer.analyze(ast, sql);
        List<SemanticError> ss = new TSqlAnalyzer(ast, sql).analyze();
        assertTrue(sem.stream().anyMatch(e -> "PROC-SEM-001".equals(e.code())));
        assertTrue(ss.stream().anyMatch(e -> "PROC-SS-001".equals(e.code())));
    }
}
