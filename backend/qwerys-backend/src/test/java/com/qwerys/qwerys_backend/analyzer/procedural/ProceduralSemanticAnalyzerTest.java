package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlLexer;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProceduralSemanticAnalyzer} — PROC-SEM-001 … PROC-SEM-010 and combined stress paths.
 */
class ProceduralSemanticAnalyzerTest {

    private static List<Token> lex(String sql, SqlDialect d) {
        return new SqlLexer(sql, d).tokenize();
    }

    private static AstNode parseOra(String sql) {
        return new SqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parse();
    }

    private static AstNode parseSs(String sql) {
        return new SqlParser(lex(sql, SqlDialect.SQLSERVER), SqlDialect.SQLSERVER).parse();
    }

    private static void assertHas(List<SemanticError> es, String code) {
        assertTrue(es.stream().anyMatch(e -> code.equals(e.code())),
                () -> es.stream().map(SemanticError::code).toList().toString());
    }

    private static void assertNo(List<SemanticError> es, String code) {
        assertFalse(es.stream().anyMatch(e -> code.equals(e.code())));
    }

    @Test
    void procSem001_assignedNeverRead_oracle() {
        String sql = "DECLARE\n nx INT;\nBEGIN\n nx := 1;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-001");
    }

    @Test
    void procSem002_readBeforeAssign_warning() {
        String sql = "DECLARE nx INT;\nBEGIN\n DBMS_OUTPUT.PUT_LINE(nx);\nEND;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-002");
    }

    @Test
    void procSem003_functionMayFallThrough_oracle() {
        String sql = "CREATE OR REPLACE FUNCTION ff_sem3 RETURN INT IS\nBEGIN\n NULL;\nEND;";
        AstNode ast = parseOra(sql);
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-003");
    }

    @Test
    void procSem004_unreachableAfterReturn() {
        String sql = "CREATE OR REPLACE PROCEDURE p_dead IS\nBEGIN\n RETURN; NULL;\nEND;";
        AstNode ast = parseOra(sql);
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-004");
    }

    @Test
    void procSem005_recursionWithoutGuard_mysql() {
        String sql = "CREATE PROCEDURE r5() NOT DETERMINISTIC SQL SECURITY INVOKER\nBEGIN\n CALL r5();\nEND;";
        AstNode ast = new SqlParser(lex(sql, SqlDialect.MYSQL), SqlDialect.MYSQL).parse();
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-005");
    }

    @Test
    void procSem006_infiniteWhile_tautology() {
        String sql = "BEGIN\n WHILE 1 = 1 LOOP\n NULL;\n END LOOP;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-006");
    }

    @Test
    void procSem007_cursorOpenCloseAcrossIfBranches() {
        String sql = "DECLARE\n"
                + " CURSOR c IS SELECT 1 FROM dual;\n"
                + "BEGIN\n"
                + " IF 1 = 1 THEN OPEN c;\n"
                + " ELSE CLOSE c;\n"
                + " END IF;\n"
                + "END;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-007");
    }

    @Test
    void procSem008_selfComparison() {
        String sql = "DECLARE nx INT DEFAULT 1;\nBEGIN\n IF nx = nx THEN NULL; END IF;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-008");
    }

    @Test
    void procSem009_numericFunctionReturnsStringLiteral() {
        String sql = "CREATE OR REPLACE FUNCTION f9 RETURN NUMBER IS\nBEGIN\n RETURN 'oops';\nEND;";
        AstNode ast = parseOra(sql);
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-009");
    }

    @Test
    void procSem009_emptyReturnOnTypedFunction() {
        String sql = "CREATE OR REPLACE FUNCTION f9b RETURN INT IS\nBEGIN\n RETURN;\nEND;";
        AstNode ast = parseOra(sql);
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-009");
    }

    @Test
    void procSem010_inParameterAssigned_oracle() {
        String sql = "CREATE OR REPLACE PROCEDURE p_in(nx IN NUMBER) IS\nBEGIN\n nx := 1;\nEND;";
        AstNode ast = parseOra(sql);
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-010");
    }

    @Test
    void procSem010_sqlServer_inStyle() {
        String sql = "CREATE PROCEDURE dbo.p_in2 @nx INT AS\nBEGIN\n SET @nx = 1;\nEND;";
        AstNode ast = parseSs(sql);
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-010");
    }

    @Test
    void procSem006_whileConditionNeverMutated() {
        String sql = "DECLARE nx INT DEFAULT 1;\nBEGIN\n WHILE nx = 1 LOOP\n NULL;\n END LOOP;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-006");
    }

    @Test
    void procSem006_suppressedWhenLeaveExitsLoop_mysql() {
        String sql = "BEGIN\n"
                + "WHILE 1=1 DO\n"
                + "lbl: LOOP LEAVE lbl; END LOOP;\n"
                + "END WHILE;\n"
                + "END;";
        AstNode ast = new MySqlPsmParser(lex(sql, SqlDialect.MYSQL), SqlDialect.MYSQL).parseBlockFromBegin();
        assertNo(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-006");
    }

    @Test
    void noFinding_whenVariableRead_afterAssign() {
        String sql = "DECLARE nx INT;\nBEGIN\n nx := 1;\n IF nx = 1 THEN NULL; END IF;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        List<SemanticError> es = ProceduralSemanticAnalyzer.analyze(ast, sql);
        assertNo(es, "PROC-SEM-001");
        assertNo(es, "PROC-SEM-002");
    }

    @Test
    void procSem003_ok_whenAllBranchesReturn() {
        String sql = "CREATE OR REPLACE FUNCTION fall RETURN INT IS\nBEGIN\n"
                + " IF 1 = 1 THEN RETURN 1; ELSE RETURN 0; END IF;\nEND;";
        AstNode ast = parseOra(sql);
        assertNo(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-003");
    }

    @Test
    void nestedBlock_scopesIndependent() {
        String sql = "DECLARE\n ax INT;\nBEGIN\n ax := 1;\n BEGIN\n DECLARE bx INT;\n BEGIN\n bx := ax;\n END;\n END;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        List<SemanticError> es = ProceduralSemanticAnalyzer.analyze(ast, sql);
        assertNo(es, "PROC-SEM-002");
    }

    @Test
    void exceptionHandler_unreachableListChecked() {
        String sql = "BEGIN\n RAISE_APPLICATION_ERROR(-20001,'x');\nEXCEPTION\n WHEN OTHERS THEN\n RETURN;\n NULL;\nEND;";
        AstNode ast = new PlSqlParser(lex(sql, SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        assertHas(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-004");
    }

    @Test
    void createFunction_withNullBody_analyzed() {
        String sql = "CREATE OR REPLACE FUNCTION z RETURN INT IS\nBEGIN\n RETURN 0;\nEND;";
        AstNode ast = parseOra(sql);
        assertNo(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-003");
    }

    @Test
    void procedure_createRoot_findsInnerBlock() {
        String sql = "CREATE OR REPLACE PROCEDURE px IS\nBEGIN\n NULL;\nEND;";
        AstNode ast = parseOra(sql);
        assertFalse(ProceduralSemanticAnalyzer.analyze(ast, sql).stream()
                .anyMatch(e -> e.code().startsWith("PROC-SEM-0") && "PROC-SEM-004".equals(e.code())));
    }

    @Test
    void deepNesting_noCrash() {
        StringBuilder sb = new StringBuilder("BEGIN\n");
        for (int i = 0; i < 8; i++) {
            sb.append(" IF 1 = 1 THEN\n");
        }
        sb.append(" NULL;\n");
        for (int i = 0; i < 8; i++) {
            sb.append(" END IF;\n");
        }
        sb.append("END;");
        AstNode ast = new PlSqlParser(lex(sb.toString(), SqlDialect.ORACLE), SqlDialect.ORACLE).parseBlock();
        ProceduralSemanticAnalyzer.analyze(ast, sb.toString());
    }

    @Test
    void nullAst_returnsEmpty() {
        assertTrue(ProceduralSemanticAnalyzer.analyze(null, "").isEmpty());
    }

    @Test
    void procSem005_recursion_with_ifGuard_noWarning() {
        String sql = "CREATE OR REPLACE PROCEDURE r_guarded IS\nBEGIN\n IF 0 = 1 THEN r_guarded;\n END IF;\nEND;";
        AstNode ast = parseOra(sql);
        assertNo(ProceduralSemanticAnalyzer.analyze(ast, sql), "PROC-SEM-005");
    }

    @Test
    void tsQlTryMerge_tryFinallyShape() {
        String sql = "BEGIN\n BEGIN TRY SELECT 1; END TRY BEGIN CATCH SELECT 2; END CATCH END;";
        AstNode ast = new TSqlParser(lex(sql, SqlDialect.SQLSERVER), SqlDialect.SQLSERVER).parseBlockFromBegin();
        ProceduralSemanticAnalyzer.analyze(ast, sql);
    }

    @Test
    void multipleFindings_accumulate() {
        String sql = "CREATE OR REPLACE FUNCTION multi RETURN INT IS\n"
                + " nx INT;\n"
                + "BEGIN\n"
                + " nx := 1;\n"
                + " WHILE 1=1 LOOP NULL; END LOOP;\n"
                + " RETURN 'bad';\n"
                + "END;";
        AstNode ast = parseOra(sql);
        List<SemanticError> es = ProceduralSemanticAnalyzer.analyze(ast, sql);
        assertTrue(es.size() >= 2);
    }
}
