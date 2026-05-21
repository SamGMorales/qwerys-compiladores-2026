package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlLexer;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PL/SQL ({@link PlSqlParser}) parsing plus {@link PlSqlAnalyzer} dialect codes PROC-ORA-001 … 008.
 */
class PlSqlParserTest {

    private static List<Token> lex(String sql) {
        return new SqlLexer(sql, SqlDialect.ORACLE).tokenize();
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
    void parseBlock_declareBeginEnd_basic() {
        String sql = "DECLARE\n  x INT;\nBEGIN\n  x := 1;\n  dbms_output.put_line(x);\nEND;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertEquals("BLOCK_STATEMENT", ast.getNodeType());
        assertNotNull(findDeep(ast, "DECLARE_SECTION"));
        assertNotNull(findDeep(ast, "STATEMENT_LIST"));
    }

    @Test
    void parseRoutineBodyAfterIsAs_implicitDeclarations() {
        String body = "n INT;\nBEGIN\n  n := 1;\nEND;";
        PlSqlParser p = new PlSqlParser(lex(body), SqlDialect.ORACLE);
        AstNode ast = p.parseRoutineBodyAfterIsAs();
        assertEquals("BLOCK_STATEMENT", ast.getNodeType());
        assertNotNull(findDeep(ast, "VARIABLE_DECLARATION"));
    }

    @Test
    void cursorDeclaration_usesIs() {
        String sql = "DECLARE\n  CURSOR c IS SELECT 1 FROM dual;\nBEGIN\n  OPEN c;\n  CLOSE c;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertNotNull(findDeep(ast, "CURSOR_DECLARATION"));
    }

    @Test
    void cursorDeclaration_usesFor() {
        String sql = "DECLARE\n  CURSOR c FOR SELECT 1 FROM dual;\nBEGIN\n  NULL;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertNotNull(findDeep(ast, "CURSOR_DECLARATION"));
    }

    @Test
    void nestedBlocks_threeLevels() {
        String sql = "BEGIN\n"
                + "  BEGIN\n"
                + "    BEGIN\n"
                + "      NULL;\n"
                + "    END;\n"
                + "  END;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        long blocks = countNodes(ast, "BLOCK_STATEMENT");
        assertTrue(blocks >= 3);
    }

    private static long countNodes(AstNode n, String type) {
        long c = type.equals(n.getNodeType()) ? 1 : 0;
        for (AstNode ch : n.getChildren()) {
            c += countNodes(ch, type);
        }
        return c;
    }

    @Test
    void exceptionBlock_whenThenHandlers() {
        String sql = "BEGIN\n"
                + "  SELECT 1 INTO x FROM dual;\n"
                + "EXCEPTION\n"
                + "  WHEN NO_DATA_FOUND THEN\n"
                + "    NULL;\n"
                + "  WHEN OTHERS THEN\n"
                + "    NULL;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        AstNode ex = findDeep(ast, "EXCEPTION_BLOCK");
        assertNotNull(ex);
        assertTrue(ex.getChildren().stream().anyMatch(ch -> "EXCEPTION_HANDLER".equals(ch.getNodeType())));
    }

    @Test
    void raiseBare_parsesAsRaiseStatement() {
        String sql = "BEGIN\n  RAISE;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertNotNull(findDeep(ast, "RAISE_STATEMENT"));
    }

    @Test
    void raiseApplicationError_lexedAsIdentifier_parsesAsRawStatement() {
        String sql = "BEGIN\n  RAISE_APPLICATION_ERROR(-20001, 'x');\nEND;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        AstNode raw = findDeep(ast, "RAW_STATEMENT");
        assertNotNull(raw);
        AstNode ex = findDeep(raw, "EXPRESSION");
        assertNotNull(ex);
        assertTrue(ex.getValue().toUpperCase().contains("RAISE_APPLICATION_ERROR"));
    }

    @Test
    void whileLoop_withEndLoopLabel() {
        String sql = "BEGIN\n"
                + "  WHILE 1 = 0 LOOP\n"
                + "    NULL;\n"
                + "  END LOOP;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertNotNull(findDeep(ast, "WHILE_STATEMENT"));
    }

    @Test
    void caseStatement_plsqlShape() {
        String sql = "BEGIN\n"
                + "  CASE 1\n"
                + "    WHEN 1 THEN NULL;\n"
                + "    ELSE NULL;\n"
                + "  END CASE;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertNotNull(findDeep(ast, "CASE_STATEMENT"));
    }

    @Test
    void parseFailure_expectedBeginOrDeclare() {
        String sql = "SELECT 1 FROM dual;";
        assertThrows(SqlParser.ParseException.class,
                () -> new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock());
    }

    @Test
    void fullCreateProcedure_sqlParser_integration() {
        String sql = "CREATE OR REPLACE PROCEDURE p AUTHID CURRENT_USER IS\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;";
        AstNode root = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        assertEquals("CREATE_PROCEDURE_STATEMENT", root.getNodeType());
        assertNotNull(findDeep(root, "BLOCK_STATEMENT"));
    }

    @Test
    void procOra001_subprogramWithoutAuthid() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\nBEGIN\n  NULL;\nEND;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql).analyze();
        assertHasCode(es, "PROC-ORA-001");
    }

    @Test
    void procOra002_selectIntoWithoutException() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "  x INT;\n"
                + "BEGIN\n"
                + "  SELECT 1 INTO x FROM dual WHERE 1=0;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql).analyze();
        assertHasCode(es, "PROC-ORA-002");
    }

    @Test
    void procOra002_selectInto_exceptionMissingPredefinedHandlers() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "  x INT;\n"
                + "BEGIN\n"
                + "  SELECT 1 INTO x FROM dual;\n"
                + "EXCEPTION\n"
                + "  WHEN DUP_VAL_ON_INDEX THEN NULL;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql).analyze();
        assertHasCode(es, "PROC-ORA-002");
    }

    @Test
    void procOra003_percentType_unknownTable() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "  x missing_table.id%TYPE;\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql, Set.of("other_tbl")).analyze();
        assertHasCode(es, "PROC-ORA-003");
    }

    @Test
    void procOra004_triggerWithCommit() {
        String sql = "CREATE OR REPLACE TRIGGER trg\n"
                + "BEFORE INSERT ON t\n"
                + "BEGIN\n"
                + "  COMMIT;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql).analyze();
        assertHasCode(es, "PROC-ORA-004");
    }

    @Test
    void procOra005_autonomousWithoutCommitRollback() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "  PRAGMA AUTONOMOUS_TRANSACTION;\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql).analyze();
        assertHasCode(es, "PROC-ORA-005");
    }

    @Test
    void procOra006_bulkCollectWithoutLimit() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "BEGIN\n"
                + "  INSERT INTO t BULK COLLECT FROM dual;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql).analyze();
        assertHasCode(es, "PROC-ORA-006");
    }

    @Test
    void procOra007_forallWithoutSaveExceptions() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "BEGIN\n"
                + "  FORALL i IN 1 .. 10 INSERT INTO t VALUES (i);\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql).analyze();
        assertHasCode(es, "PROC-ORA-007");
    }

    @Test
    void procOra008_explicitCursorWithOpen() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "  CURSOR c IS SELECT 1 FROM dual;\n"
                + "BEGIN\n"
                + "  OPEN c;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.ORACLE).parse();
        List<SemanticError> es = new PlSqlAnalyzer(ast, sql).analyze();
        assertHasCode(es, "PROC-ORA-008");
    }

    @Test
    void stress_nestedExceptionHandlers() {
        String sql = "BEGIN\n"
                + "  BEGIN\n"
                + "    NULL;\n"
                + "  EXCEPTION\n"
                + "    WHEN OTHERS THEN\n"
                + "      BEGIN\n"
                + "        RAISE;\n"
                + "      END;\n"
                + "  END;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertTrue(countNodes(ast, "EXCEPTION_BLOCK") >= 1);
    }

    @Test
    void stress_deepNestedIfEndIf() {
        StringBuilder sb = new StringBuilder("BEGIN\n");
        for (int i = 0; i < 6; i++) {
            sb.append("  IF 1 = 1 THEN\n");
        }
        sb.append("    NULL;\n");
        for (int i = 0; i < 6; i++) {
            sb.append("  END IF;\n");
        }
        sb.append("END;");
        AstNode ast = new PlSqlParser(lex(sb.toString()), SqlDialect.ORACLE).parseBlock();
        assertTrue(countNodes(ast, "IF_STATEMENT") >= 6);
    }

    @Test
    void forLoop_cursorForm() {
        String sql = "BEGIN\n"
                + "  FOR r IN (SELECT 1 x FROM dual) LOOP\n"
                + "    NULL;\n"
                + "  END LOOP;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertNotNull(findDeep(ast, "FOR_STATEMENT"));
    }

    @Test
    void openFetchClose_flow() {
        String sql = "DECLARE\n"
                + "  CURSOR c IS SELECT 1 FROM dual;\n"
                + "  v INT;\n"
                + "BEGIN\n"
                + "  OPEN c;\n"
                + "  FETCH c INTO v;\n"
                + "  CLOSE c;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        assertNotNull(findDeep(ast, "OPEN_CURSOR_STATEMENT"));
        assertNotNull(findDeep(ast, "FETCH_STATEMENT"));
        assertNotNull(findDeep(ast, "CLOSE_CURSOR_STATEMENT"));
    }

    @Test
    void proceduralSemantic_mergeWithPlSqlAnalyzer() {
        String sql = "DECLARE\n"
                + "  y INT DEFAULT 1;\n"
                + "BEGIN\n"
                + "  y := 2;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql), SqlDialect.ORACLE).parseBlock();
        List<SemanticError> sem = ProceduralSemanticAnalyzer.analyze(ast, sql);
        List<SemanticError> ora = new PlSqlAnalyzer(ast, sql).analyze();
        assertTrue(sem.stream().anyMatch(e -> "PROC-SEM-001".equals(e.code())));
        assertTrue(ora.isEmpty() || ora.stream().noneMatch(e -> e.code().startsWith("PROC-ORA")));
    }
}
