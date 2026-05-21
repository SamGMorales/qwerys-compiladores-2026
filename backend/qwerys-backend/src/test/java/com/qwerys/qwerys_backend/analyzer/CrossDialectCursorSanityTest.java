package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.analyzer.procedural.MySqlPsmAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.PlPgSqlAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.ProceduralSemanticAnalyzer;
import com.qwerys.qwerys_backend.analyzer.procedural.TSqlAnalyzer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests matching documented procedural cursor patterns (SQL Server DECLARE CURSOR … WHERE col = …,
 * MySQL LEAVE/labels, PostgreSQL OPEN without DECLARE).
 */
class CrossDialectCursorSanityTest {

    private static AstNode parse(SqlDialect d, String sql) {
        return new SqlParser(new SqlLexer(sql, d).tokenize(), d).parse();
    }

    @Test
    void sqlServer_wholeBatch_whileFetchStatus_parses() {
        String sql = "BEGIN\n"
                + "  DECLARE @id INT;\n"
                + "  DECLARE c CURSOR FOR SELECT id FROM dbo.empleados WHERE dept_id = 10;\n"
                + "  OPEN c;\n"
                + "  FETCH NEXT FROM c INTO @id;\n"
                + "  WHILE @@FETCH_STATUS = 0\n"
                + "  BEGIN\n"
                + "    UPDATE dbo.empleados SET nombre = N'Actualizado' WHERE id = @id;\n"
                + "    FETCH NEXT FROM c INTO @id;\n"
                + "  END;\n"
                + "  CLOSE c;\n"
                + "  DEALLOCATE c;\n"
                + "END;";
        AstNode ast = parse(SqlDialect.SQLSERVER, sql);
        assertNotNull(ast);
        List<SemanticError> findings = new ArrayList<>(new TSqlAnalyzer(ast, sql).analyze());
        findings.addAll(ProceduralSemanticAnalyzer.analyze(ast, sql));
        assertTrue(findings.stream().noneMatch(e -> e.message().contains("Expected SEMICOLON")),
                findings::toString);
        assertTrue(findings.stream().noneMatch(e -> "PROC-SEM-006".equals(e.code())),
                () -> "unexpected infinite-loop heuristic: " + findings);
    }

    @Test
    void postgres_openUndeclaredCursor_reportsProc026() {
        String sql = "BEGIN\n"
                + "  OPEN c;\n"
                + "  LOOP\n"
                + "    FETCH c INTO r;\n"
                + "    EXIT WHEN NOT FOUND;\n"
                + "    UPDATE empleados SET nombre = 'Actualizado' WHERE id = r.id;\n"
                + "  END LOOP;\n"
                + "  CLOSE c;\n"
                + "END $$;";
        AstNode ast = parse(SqlDialect.POSTGRESQL, sql);
        List<SemanticError> findings = new ArrayList<>(new PlPgSqlAnalyzer(ast, sql).analyze());
        assertTrue(findings.stream().anyMatch(e -> "PROC-026".equals(e.code())),
                () -> "expected PROC-026 for undeclared cursor c: " + findings);
    }

    @Test
    void mysql_labeledLoop_leave_noFalseProc002OnKeywords() {
        String sql = "CREATE PROCEDURE p_cursor_update()\n"
                + "BEGIN\n"
                + "  DECLARE done INT DEFAULT FALSE;\n"
                + "  DECLARE v_id INT;\n"
                + "  DECLARE c CURSOR FOR SELECT id FROM empleados WHERE activo = 1;\n"
                + "  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;\n"
                + "  OPEN c;\n"
                + "  read_loop: LOOP\n"
                + "    FETCH c INTO v_id;\n"
                + "    IF done THEN\n"
                + "      LEAVE read_loop;\n"
                + "    END IF;\n"
                + "    UPDATE empleados SET nombre = 'Actualizado' WHERE id = v_id;\n"
                + "  END LOOP read_loop;\n"
                + "  CLOSE c;\n"
                + "END;";
        AstNode ast = parse(SqlDialect.MYSQL, sql);
        List<SemanticError> findings = new ArrayList<>(new MySqlPsmAnalyzer(ast, sql).analyze());
        assertTrue(findings.stream().noneMatch(e -> "PROC-002".equals(e.code())),
                () -> findings.toString());
        assertTrue(findings.stream().noneMatch(e -> "PROC-012".equals(e.code())),
                () -> findings.toString());
    }

    @Test
    void sqlServer_nPrefixedUnicodeString_notDeclaredAsVariableN() {
        String sql = "BEGIN\n"
                + "  DECLARE @id INT;\n"
                + "  UPDATE dbo.empleados SET nombre = N'Actualizado' WHERE id = @id;\n"
                + "END;";
        AstNode ast = parse(SqlDialect.SQLSERVER, sql);
        List<SemanticError> findings = new ArrayList<>(new TSqlAnalyzer(ast, sql).analyze());
        assertTrue(findings.stream().noneMatch(e -> "PROC-002".equals(e.code())
                        && e.message().toUpperCase(Locale.ROOT).contains("\"N\"")),
                findings::toString);
    }

    @Test
    void postgres_doBlock_openUndeclaredCursor_reportsProc026() {
        String sql = "DO $$\n"
                + "BEGIN\n"
                + "  OPEN c;\n"
                + "  LOOP\n"
                + "    FETCH c INTO v_id;\n"
                + "    EXIT WHEN NOT FOUND;\n"
                + "    UPDATE empleados SET nombre = 'Ok' WHERE id = v_id;\n"
                + "  END LOOP;\n"
                + "  CLOSE c;\n"
                + "END $$ LANGUAGE plpgsql;";
        AstNode ast = parse(SqlDialect.POSTGRESQL, sql);
        List<SemanticError> findings = new ArrayList<>(new PlPgSqlAnalyzer(ast, sql).analyze());
        assertTrue(findings.stream().anyMatch(e -> "PROC-026".equals(e.code())),
                () -> "expected PROC-026 for undeclared cursor in DO block: " + findings);
    }

    @Test
    void mysql_whileWithBareLeave_noInfiniteLoopProcSem006() {
        String sql = "CREATE PROCEDURE p_cursor_delete()\n"
                + "BEGIN\n"
                + "  DECLARE v_id INT;\n"
                + "  DECLARE done INT DEFAULT 0;\n"
                + "  DECLARE c CURSOR FOR SELECT id FROM temp_borrar;\n"
                + "  DECLARE CONTINUE HANDLER FOR SQLSTATE '02000' SET done = 1;\n"
                + "  OPEN c;\n"
                + "  WHILE done = 0 DO\n"
                + "    FETCH c INTO v_id;\n"
                + "    IF done THEN\n"
                + "      LEAVE;\n"
                + "    END IF;\n"
                + "    DELETE FROM cola WHERE ref_id = v_id;\n"
                + "  END WHILE;\n"
                + "  CLOSE c;\n"
                + "END;";
        AstNode ast = parse(SqlDialect.MYSQL, sql);
        List<SemanticError> findings = new ArrayList<>(new MySqlPsmAnalyzer(ast, sql).analyze());
        findings.addAll(ProceduralSemanticAnalyzer.analyze(ast, sql));
        assertTrue(findings.stream().noneMatch(e -> "PROC-SEM-006".equals(e.code())),
                findings::toString);
    }
}
