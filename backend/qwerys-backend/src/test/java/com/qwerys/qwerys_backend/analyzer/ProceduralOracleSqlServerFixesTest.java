package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.analyzer.procedural.MySqlPsmAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.PlPgSqlAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.PlSqlAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.ProceduralSemanticAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.TSqlAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Oracle PL/SQL named-unit headers and SQL Server routine parameters / RETURN.
 */
class ProceduralOracleSqlServerFixesTest {

    private static AstNode parseOracle(String sql) {
        List<Token> tokens = new SqlLexer(sql, SqlDialect.ORACLE).tokenize();
        return new SqlParser(tokens, SqlDialect.ORACLE).parse();
    }

    private static AstNode parseSqlServer(String sql) {
        List<Token> tokens = new SqlLexer(sql, SqlDialect.SQLSERVER).tokenize();
        return new SqlParser(tokens, SqlDialect.SQLSERVER).parse();
    }

    private static AstNode findChild(AstNode parent, String nodeType) {
        if (parent == null) {
            return null;
        }
        for (AstNode c : parent.getChildren()) {
            if (nodeType.equals(c.getNodeType())) {
                return c;
            }
        }
        return null;
    }

    private static AstNode parseMysql(String sql) {
        List<Token> tokens = new SqlLexer(sql, SqlDialect.MYSQL).tokenize();
        return new SqlParser(tokens, SqlDialect.MYSQL).parse();
    }

    private static AstNode parsePostgreSql(String sql) {
        List<Token> tokens = new SqlLexer(sql, SqlDialect.POSTGRESQL).tokenize();
        return new SqlParser(tokens, SqlDialect.POSTGRESQL).parse();
    }

    private static AstNode findDeep(AstNode node, String nodeType) {
        AstNode hit = findChild(node, nodeType);
        if (hit != null) {
            return hit;
        }
        for (AstNode c : node.getChildren()) {
            AstNode d = findDeep(c, nodeType);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private static void assertNoProcScopeNoise(List<SemanticError> findings) {
        assertTrue(
                findings.stream().noneMatch(e -> "PROC-002".equals(e.code()) || "PROC-004".equals(e.code())),
                () -> "unexpected PROC scope errors: "
                        + findings.stream()
                                .filter(e -> "PROC-002".equals(e.code()) || "PROC-004".equals(e.code()))
                                .map(SemanticError::code)
                                .toList());
    }

    @Test
    void oracleCreateOrReplaceProcedure_accepted() {
        String sql = "CREATE OR REPLACE PROCEDURE p IS\n"
                + "  x INT;\n"
                + "BEGIN\n"
                + "  x := 10;\n"
                + "  NULL;\n"
                + "END;";
        AstNode root = parseOracle(sql);
        assertEquals("CREATE_PROCEDURE_STATEMENT", root.getNodeType());
        assertNotNull(findDeep(root, "BLOCK_STATEMENT"));
        assertNotNull(findChild(root, "OBJECT_NAME"));
    }

    @Test
    void sqlServerFunction_ifWithoutThen_parsesLikeTsql() {
        String sql = "CREATE FUNCTION dbo.fn(@a INT)\n"
                + "RETURNS INT\n"
                + "AS\n"
                + "BEGIN\n"
                + "  IF @a > 0 RETURN 1;\n"
                + "  RETURN 0;\n"
                + "END;";
        AstNode ast = parseSqlServer(sql);
        assertEquals("CREATE_FUNCTION_STATEMENT", ast.getNodeType());
        AstNode ifn = findDeep(ast, "IF_STATEMENT");
        assertNotNull(ifn);
    }

    @Test
    void sqlServerProcedure_declareInsideBegin_registersLocals() {
        String sql = "CREATE PROCEDURE dbo.p AS\n"
                + "BEGIN\n"
                + "    DECLARE @x INT;\n"
                + "    SET @x = 10;\n"
                + "END;";
        AstNode ast = parseSqlServer(sql);
        List<SemanticError> findings = new ArrayList<>(new TSqlAnalyzer(ast, sql).analyze());
        assertTrue(
                findings.stream().noneMatch(e -> "PROC-002".equals(e.code()) || "PROC-004".equals(e.code())),
                () -> findings.stream().map(SemanticError::code).toList().toString());
    }

    @Test
    void postgresCreateFunction_parameterList_populated() {
        String sql = "CREATE FUNCTION f_test(a int)\n"
                + "RETURNS int\n"
                + "LANGUAGE plpgsql\n"
                + "AS $$\n"
                + "BEGIN\n"
                + "  IF a > 0 THEN RETURN 1; END IF;\n"
                + "END;\n"
                + "$$;";
        AstNode root = parsePostgreSql(sql);
        assertEquals("CREATE_FUNCTION_STATEMENT", root.getNodeType());
        AstNode pl = findChild(root, "PARAMETER_LIST");
        assertNotNull(pl);
        assertTrue(pl.getValue().toLowerCase().contains("a"));
    }

    @Test
    void postgresCreateOrReplaceProcedure_parses() {
        String sql = "CREATE OR REPLACE PROCEDURE p_test()\n"
                + "LANGUAGE plpgsql\n"
                + "AS $$\n"
                + "DECLARE x int;\n"
                + "BEGIN\n"
                + "    x := 10;\n"
                + "END;\n"
                + "$$;";
        AstNode root = parsePostgreSql(sql);
        assertEquals("CREATE_PROCEDURE_STATEMENT", root.getNodeType());
        assertNotNull(findDeep(root, "DECLARE_SECTION"));
    }

    @Test
    void mysqlProcedure_declareInsideBegin_noUndeclaredNoise() {
        String sql = "CREATE PROCEDURE p()\nBEGIN\n  DECLARE x INT;\n  SET x = 10;\nEND;";
        AstNode ast = parseMysql(sql);
        List<SemanticError> findings = new ArrayList<>(new MySqlPsmAnalyzer(ast, sql).analyze());
        assertTrue(
                findings.stream().noneMatch(e -> "PROC-002".equals(e.code()) || "PROC-004".equals(e.code())),
                () -> findings.stream().map(SemanticError::code).toList().toString());
    }

    @Test
    void oracleCreateProcedure_declarationsWithoutDeclareBeforeBegin() {
        String sql = "CREATE PROCEDURE p IS x NUMBER; BEGIN x := 10; END;";
        AstNode root = parseOracle(sql);
        assertEquals("CREATE_PROCEDURE_STATEMENT", root.getNodeType());
        AstNode block = findDeep(root, "BLOCK_STATEMENT");
        assertNotNull(block);
        AstNode declSec = findChild(block, "DECLARE_SECTION");
        assertNotNull(declSec);
        assertEquals(1, declSec.getChildren().size());
        assertEquals("VARIABLE_DECLARATION", declSec.getChildren().get(0).getNodeType());
        assertEquals("x", declSec.getChildren().get(0).getValue());
    }

    @Test
    void oracleCreateProcedure_isBeginOnlySkipsDeclareSection() {
        String sql = "CREATE PROCEDURE p IS BEGIN NULL; END;";
        AstNode root = parseOracle(sql);
        AstNode block = findDeep(root, "BLOCK_STATEMENT");
        assertNotNull(block);
        assertNull(findChild(block, "DECLARE_SECTION"));
    }

    @Test
    void sqlServerCreateProcedure_parameterListFromAtParams() {
        String sql = "CREATE PROCEDURE dbo.pr @x INT AS BEGIN SET @x = 10; END;";
        AstNode root = parseSqlServer(sql);
        assertEquals("CREATE_PROCEDURE_STATEMENT", root.getNodeType());
        AstNode pl = findChild(root, "PARAMETER_LIST");
        assertNotNull(pl);
        assertTrue(pl.getValue().contains("@x"));
    }

    @Test
    void sqlServerCreateFunction_parameterListFromParentheses() {
        String sql = "CREATE FUNCTION dbo.fn(@a INT) RETURNS INT AS BEGIN RETURN @a + 1; END;";
        AstNode root = parseSqlServer(sql);
        assertEquals("CREATE_FUNCTION_STATEMENT", root.getNodeType());
        AstNode pl = findChild(root, "PARAMETER_LIST");
        assertNotNull(pl);
        assertTrue(pl.getValue().contains("@a"));
    }

    @Test
    void sqlServerProcedure_noUndeclaredErrorsForFormalParameter() {
        String sql = "CREATE PROCEDURE dbo.pr @x INT AS BEGIN SET @x = 10; END;";
        AstNode ast = parseSqlServer(sql);
        List<SemanticError> findings = new ArrayList<>(new TSqlAnalyzer(ast, sql).analyze());
        assertTrue(findings.stream().noneMatch(e -> "PROC-002".equals(e.code()) || "PROC-004".equals(e.code())));
    }

    @Test
    void sqlServerFunction_returnParsed_noFalseMissingReturnPath() {
        String sql = "CREATE FUNCTION dbo.fn(@a INT) RETURNS INT AS BEGIN RETURN @a + 1; END;";
        AstNode ast = parseSqlServer(sql);
        List<SemanticError> findings = new ArrayList<>(new TSqlAnalyzer(ast, sql).analyze());
        findings.addAll(ProceduralSemanticAnalyzer.analyze(ast, sql));
        assertTrue(findings.stream().noneMatch(e -> "PROC-SEM-003".equals(e.code())));
        assertTrue(findings.stream().noneMatch(e -> "PROC-002".equals(e.code())));
    }

    @Test
    void oracleFunction_formalParameterSeen_missingReturnStillReported() {
        String sql = "CREATE FUNCTION f(a NUMBER) RETURN NUMBER IS BEGIN IF a > 0 THEN RETURN 1; END IF; END;";
        AstNode ast = parseOracle(sql);
        assertEquals("CREATE_FUNCTION_STATEMENT", ast.getNodeType());
        AstNode pl = findChild(ast, "PARAMETER_LIST");
        assertNotNull(pl, "Oracle function should expose PARAMETER_LIST");
        assertTrue(
                RoutineParameterSupport.canonicalFormalNames(pl).contains("A"),
                () -> "formals: " + RoutineParameterSupport.canonicalFormalNames(pl) + " pl=" + pl.getValue());
        assertNotNull(findDeep(ast, "RETURN_STATEMENT"), "RETURN should parse as RETURN_STATEMENT");
        AstNode body = findDeep(ast, "BLOCK_STATEMENT");
        assertNotNull(body);
        AstNode sl = findChild(body, "STATEMENT_LIST");
        assertNotNull(sl);
        assertEquals(1, sl.getChildren().size(), () -> ast.toString());
        AstNode ifn = sl.getChildren().get(0);
        assertEquals("IF_STATEMENT", ifn.getNodeType());
        assertNull(findChild(ifn, "ELSE_BLOCK"), () -> ifn.toString());
        List<SemanticError> findings = new ArrayList<>(new PlSqlAnalyzer(ast, sql).analyze());
        findings.addAll(ProceduralSemanticAnalyzer.analyze(ast, sql));
        assertTrue(findings.stream().noneMatch(e -> "PROC-002".equals(e.code())));
        assertTrue(
                findings.stream().anyMatch(e -> "PROC-SEM-003".equals(e.code())),
                () -> "expected PROC-SEM-003 among: "
                        + findings.stream().map(SemanticError::code).toList());
    }

    /**
     * FOR iterator is implicit; table/column names inside the driver SELECT must not be reported as
     * undeclared procedural variables (PROC-002).
     */
    @Test
    void oracle_forInSelectLoop_noProc002FromEmbeddedSqlIdentifiers() {
        String sql = "CREATE OR REPLACE PROCEDURE p_for_cursor AS\n"
                + "BEGIN\n"
                + "  FOR r IN (SELECT id FROM empleados WHERE activo = 1) LOOP\n"
                + "    DELETE FROM auditoria WHERE empleado_id = r.id AND antigua = 1;\n"
                + "  END LOOP;\n"
                + "END;";
        AstNode ast = parseOracle(sql);
        List<SemanticError> findings = new ArrayList<>(new PlSqlAnalyzer(ast, sql).analyze());
        assertNoProcScopeNoise(findings);
    }

    /** T-SQL {@code WHILE cond BEGIN … END} parses as WHILE_STATEMENT with a statement list body. */
    @Test
    void sqlServer_whileBeginEnd_cursorLoop_parses() {
        String sql = "CREATE PROCEDURE dbo.p_while_cursor AS\n"
                + "BEGIN\n"
                + "    DECLARE @id INT;\n"
                + "    DECLARE c CURSOR FOR SELECT id FROM dbo.temp_ids;\n"
                + "    OPEN c;\n"
                + "    FETCH NEXT FROM c INTO @id;\n"
                + "    WHILE @@FETCH_STATUS = 0\n"
                + "    BEGIN\n"
                + "        DELETE FROM dbo.cola WHERE ref_id = @id;\n"
                + "        FETCH NEXT FROM c INTO @id;\n"
                + "    END;\n"
                + "    CLOSE c;\n"
                + "    DEALLOCATE c;\n"
                + "END;";
        AstNode ast = parseSqlServer(sql);
        AstNode wh = findDeep(ast, "WHILE_STATEMENT");
        assertNotNull(wh, () -> ast.toString());
        AstNode body = findChild(wh, "STATEMENT_LIST");
        assertNotNull(body, () -> wh.toString());
        assertFalse(body.getChildren().isEmpty(), () -> body.toString());
    }

    @Test
    void sqlServer_whileBeginEnd_cursorLoop_noProcScopeNoise() {
        String sql = "CREATE PROCEDURE dbo.p_while_cursor AS\n"
                + "BEGIN\n"
                + "    DECLARE @id INT;\n"
                + "    DECLARE c CURSOR FOR SELECT id FROM dbo.temp_ids;\n"
                + "    OPEN c;\n"
                + "    FETCH NEXT FROM c INTO @id;\n"
                + "    WHILE @@FETCH_STATUS = 0\n"
                + "    BEGIN\n"
                + "        DELETE FROM dbo.cola WHERE ref_id = @id;\n"
                + "        FETCH NEXT FROM c INTO @id;\n"
                + "    END;\n"
                + "    CLOSE c;\n"
                + "    DEALLOCATE c;\n"
                + "END;";
        AstNode ast = parseSqlServer(sql);
        List<SemanticError> findings = new ArrayList<>(new TSqlAnalyzer(ast, sql).analyze());
        assertNoProcScopeNoise(findings);
    }

    @Test
    void postgres_do_forInSelect_noProc002FromEmbeddedSqlIdentifiers() {
        String sql = "DO $$\n"
                + "BEGIN\n"
                + "  FOR r IN SELECT id FROM empleados WHERE dept_id = 2 LOOP\n"
                + "    UPDATE log_empleados SET procesado = TRUE WHERE empleado_id = r.id;\n"
                + "  END LOOP;\n"
                + "END $$ LANGUAGE plpgsql;";
        AstNode ast = parsePostgreSql(sql);
        AstNode fo = findDeep(ast, "FOR_STATEMENT");
        assertNotNull(fo, () -> ast.toString());
        List<SemanticError> findings = new ArrayList<>(new PlPgSqlAnalyzer(ast, sql).analyze());
        assertNoProcScopeNoise(findings);
    }

    /** MySQL DECLARE CONTINUE HANDLER … FOR NOT FOUND / SQLSTATE parses inside routine DECLARE preamble. */
    @Test
    void mysql_procedure_continueHandlers_parse_noImmediateSyntaxFailure() {
        String sqlNotFound = "CREATE PROCEDURE p_nf()\n"
                + "BEGIN\n"
                + "  DECLARE done INT DEFAULT 0;\n"
                + "  DECLARE v_id INT;\n"
                + "  DECLARE c CURSOR FOR SELECT id FROM empleados WHERE activo = 1;\n"
                + "  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;\n"
                + "  OPEN c;\n"
                + "END;";
        AstNode ast1 = parseMysql(sqlNotFound);
        assertNotNull(findDeep(ast1, "HANDLER_DECLARATION"), () -> ast1.toString());

        String sqlState = "CREATE PROCEDURE p_sqlstate()\n"
                + "BEGIN\n"
                + "  DECLARE done INT DEFAULT 0;\n"
                + "  DECLARE v_id INT;\n"
                + "  DECLARE c CURSOR FOR SELECT id FROM t;\n"
                + "  DECLARE CONTINUE HANDLER FOR SQLSTATE '02000' SET done = 1;\n"
                + "  OPEN c;\n"
                + "END;";
        AstNode ast2 = parseMysql(sqlState);
        assertNotNull(findDeep(ast2, "HANDLER_DECLARATION"), () -> ast2.toString());
        List<SemanticError> findings = new ArrayList<>(new MySqlPsmAnalyzer(ast2, sqlState).analyze());
        assertNoProcScopeNoise(findings);
    }
}
