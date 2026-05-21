package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlLexer;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.StatementSplitter;
import com.qwerys.qwerys_backend.analyzer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link MySqlPsmParser} + {@link MySqlPsmAnalyzer} PROC-MY-001 … 008 + {@code DELIMITER}. */
class MySqlPsmParserTest {

    private static List<Token> lex(String sql) {
        return new SqlLexer(sql, SqlDialect.MYSQL).tokenize();
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
    void parseBlock_declareBeginEnd() {
        String sql = "DECLARE x INT DEFAULT 0;\nBEGIN\n  SET x = 1;\n  SELECT x;\nEND;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlock();
        assertEquals("BLOCK_STATEMENT", ast.getNodeType());
    }

    @Test
    void whileDo_endWhile_mysql() {
        String sql = "BEGIN\n"
                + "WHILE 1=0 DO\n"
                + "SELECT 1;\n"
                + "END WHILE;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "WHILE_STATEMENT"));
    }

    @Test
    void stress_nestedWhileDo() {
        String sql = "BEGIN\n"
                + "WHILE 1=0 DO\n"
                + "  WHILE 1=0 DO\n"
                + "    SELECT 1;\n"
                + "  END WHILE;\n"
                + "END WHILE;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertTrue(countType(ast, "WHILE_STATEMENT") >= 2);
    }

    @Test
    void labeledLoop_parses() {
        String sql = "BEGIN\n"
                + "  lbl: LOOP\n"
                + "    LEAVE lbl;\n"
                + "  END LOOP lbl;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "LOOP_STATEMENT"));
    }

    @Test
    void iterateWithoutLabel_fails() {
        String sql = "BEGIN\n  ITERATE;\nEND;";
        assertThrows(SqlParser.ParseException.class,
                () -> new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin());
    }

    @Test
    void iterateStatement_parses() {
        String sql = "BEGIN\n  lb: LOOP\n    ITERATE lb;\n  END LOOP;\nEND;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "ITERATE_STATEMENT"));
    }

    @Test
    void returnInFunction_parses() {
        String sql = "RETURN 42;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseReturnStatement();
        assertEquals("RETURN_STATEMENT", ast.getNodeType());
    }

    @Test
    void signalStatement_parses() {
        String sql = "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'x';";
        AstNode ast = new MySqlPsmParser(lex("BEGIN " + sql + " END;"), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "SIGNAL_STATEMENT"));
    }

    @Test
    void resignalStatement_parses() {
        String sql = "BEGIN RESIGNAL SET MESSAGE_TEXT = 'y'; END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "RESIGNAL_STATEMENT"));
    }

    @Test
    void exitHandlerForSqlexception_parses() {
        String sql = "DECLARE CONTINUE HANDLER FOR SQLEXCEPTION BEGIN END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlock();
        assertNotNull(findDeep(ast, "HANDLER_DECLARATION"));
    }

    @Test
    void nestedBeginHandlers() {
        String sql = "BEGIN\n"
                + "  DECLARE EXIT HANDLER FOR NOT FOUND BEGIN END;\n"
                + "  BEGIN\n"
                + "    SELECT 1;\n"
                + "  END;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertTrue(countType(ast, "BLOCK_STATEMENT") >= 2);
    }

    private static int countType(AstNode n, String t) {
        int c = t.equals(n.getNodeType()) ? 1 : 0;
        for (AstNode ch : n.getChildren()) {
            c += countType(ch, t);
        }
        return c;
    }

    @Test
    void whileLoop_mysql() {
        String sql = "BEGIN\n  WHILE 1 = 0 DO\n    SELECT 1;\n  END WHILE;\nEND;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "WHILE_STATEMENT"));
    }

    @Test
    void delimiterScript_splitsProcedureAndResets() {
        String raw = "DELIMITER //\n"
                + "CREATE PROCEDURE p1()\n"
                + "BEGIN\n"
                + "  SELECT 1;\n"
                + "END//\n"
                + "DELIMITER ;\n"
                + "SELECT 2;";
        List<String> parts = StatementSplitter.split(raw, SqlDialect.MYSQL);
        assertTrue(parts.size() >= 3, parts.toString());
        assertTrue(parts.stream().anyMatch(s -> s.toUpperCase().contains("CREATE PROCEDURE")));
        String tail = parts.get(parts.size() - 1);
        assertTrue(tail.contains("SELECT 2"), tail);
    }

    @Test
    void fullCreateProcedure_sqlParser() {
        String sql = "CREATE PROCEDURE p2()\n"
                + "NOT DETERMINISTIC\n"
                + "SQL SECURITY INVOKER\n"
                + "BEGIN\n"
                + "  SELECT 1;\n"
                + "END;";
        AstNode root = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertEquals("CREATE_PROCEDURE_STATEMENT", root.getNodeType());
    }

    @Test
    void procMy001_noDeterministic() {
        String sql = "CREATE PROCEDURE p3() BEGIN SELECT 1; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertHasCode(new MySqlPsmAnalyzer(ast, sql).analyze(), "PROC-MY-001");
    }

    @Test
    void procMy002_noSqlSecurity() {
        String sql = "CREATE PROCEDURE p4() NOT DETERMINISTIC BEGIN SELECT 1; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertHasCode(new MySqlPsmAnalyzer(ast, sql).analyze(), "PROC-MY-002");
    }

    @Test
    void procMy003_fetchWithoutHandler() {
        String sql = "CREATE PROCEDURE p5() NOT DETERMINISTIC SQL SECURITY INVOKER\nBEGIN\n"
                + "  DECLARE c CURSOR FOR SELECT 1;\n"
                + "  DECLARE v INT;\n"
                + "  OPEN c;\n"
                + "  FETCH c INTO v;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertHasCode(new MySqlPsmAnalyzer(ast, sql).analyze(), "PROC-MY-003");
    }

    @Test
    void procMy004_exitHandlerSqlexception() {
        String sql = "CREATE PROCEDURE p6() NOT DETERMINISTIC SQL SECURITY DEFINER\nBEGIN\n"
                + "  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN END;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertHasCode(new MySqlPsmAnalyzer(ast, sql).analyze(), "PROC-MY-004");
    }

    @Test
    void procMy005_signalWithoutSqlstate() {
        String sql = "CREATE PROCEDURE p7() NOT DETERMINISTIC SQL SECURITY INVOKER\nBEGIN\n"
                + "  SIGNAL mycond;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertHasCode(new MySqlPsmAnalyzer(ast, sql).analyze(), "PROC-MY-005");
    }

    @Test
    void procMy006_triggerUpdatesSameTable() {
        String sql = "CREATE TRIGGER trg BEFORE UPDATE ON orders FOR EACH ROW BEGIN\n"
                + "  UPDATE orders SET x = 1;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertHasCode(new MySqlPsmAnalyzer(ast, sql).analyze(), "PROC-MY-006");
    }

    @Test
    void procMy007_functionModifiesSqlData() {
        String sql = "CREATE FUNCTION f1() RETURNS INT MODIFIES SQL DATA\nBEGIN RETURN 1; END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertHasCode(new MySqlPsmAnalyzer(ast, sql).analyze(), "PROC-MY-007");
    }

    @Test
    void procMy008_outParamUnassigned() {
        String sql = "CREATE PROCEDURE p8(OUT px INT) NOT DETERMINISTIC SQL SECURITY INVOKER\nBEGIN\n"
                + "  SELECT 1;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        assertHasCode(new MySqlPsmAnalyzer(ast, sql).analyze(), "PROC-MY-008");
    }

    @Test
    void repeatUntil_insideBegin_parses() {
        String sql = "BEGIN REPEAT SELECT 1; UNTIL 1=1 END REPEAT; END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "REPEAT_STATEMENT"));
    }

    @Test
    void stress_sequentialRepeatBlocks_threeTimes() {
        String sql = "BEGIN\n"
                + "REPEAT SELECT 1; UNTIL 1=1 END REPEAT;\n"
                + "REPEAT SELECT 1; UNTIL 1=1 END REPEAT;\n"
                + "REPEAT SELECT 1; UNTIL 1=1 END REPEAT;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertTrue(countType(ast, "REPEAT_STATEMENT") >= 3);
    }

    @Test
    void threeSequentialWhileBlocks() {
        String sql = "BEGIN\n"
                + "WHILE 1=0 DO SELECT 1; END WHILE;\n"
                + "WHILE 1=0 DO SELECT 1; END WHILE;\n"
                + "WHILE 1=0 DO SELECT 1; END WHILE;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertTrue(countType(ast, "WHILE_STATEMENT") >= 3);
    }

    @Test
    void caseStatement_mysql() {
        String sql = "BEGIN\n"
                + "  CASE 1\n"
                + "    WHEN 1 THEN SELECT 1;\n"
                + "    ELSE SELECT 0;\n"
                + "  END CASE;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "CASE_STATEMENT"));
    }

    @Test
    void ifStatement_nested() {
        String sql = "BEGIN\n"
                + "  IF 1 = 1 THEN\n"
                + "    IF 1 = 1 THEN\n"
                + "      SELECT 1;\n"
                + "    END IF;\n"
                + "  END IF;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql), SqlDialect.MYSQL).parseBlockFromBegin();
        assertTrue(countType(ast, "IF_STATEMENT") >= 2);
    }

    @Test
    void merge_myAnalyzerAndSemantic() {
        String sql = "CREATE PROCEDURE p9() NOT DETERMINISTIC SQL SECURITY INVOKER\nBEGIN\n"
                + "  DECLARE v INT;\n"
                + "  SET v = 1;\n"
                + "END;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.MYSQL).parse();
        List<SemanticError> sem = ProceduralSemanticAnalyzer.analyze(ast, sql);
        List<SemanticError> my = new MySqlPsmAnalyzer(ast, sql).analyze();
        assertTrue(sem.stream().anyMatch(e -> "PROC-SEM-001".equals(e.code())));
        assertTrue(my.stream().noneMatch(e -> "PROC-MY-001".equals(e.code())));
    }
}
