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

/** {@link PlPgSqlParser} + {@link PlPgSqlAnalyzer} codes PROC-PG-001 … 010. */
class PlPgSqlParserTest {

    private static List<Token> lex(String sql) {
        return new SqlLexer(sql, SqlDialect.POSTGRESQL).tokenize();
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
    void stripDollarQuotes_tagged() {
        String dq = "$a$hello;$a$";
        assertEquals("hello;", PlPgSqlParser.stripDollarQuotes(dq));
    }

    @Test
    void parseInnerBody_dollarQuoted_declareBegin() {
        AstNode ast = PlPgSqlParser.parseInnerBody(
                "$$ DECLARE x int; BEGIN x := 1; RAISE NOTICE '%', x; END; $$",
                SqlDialect.POSTGRESQL);
        assertEquals("BLOCK_STATEMENT", ast.getNodeType());
        assertNotNull(findDeep(ast, "DECLARE_SECTION"));
    }

    @Test
    void parseInnerBody_plain_beginOnly() {
        AstNode ast = PlPgSqlParser.parseInnerBody(
                "BEGIN PERFORM 1; END;",
                SqlDialect.POSTGRESQL);
        assertNotNull(findDeep(ast, "PERFORM_STATEMENT"));
    }

    @Test
    void parseReturnQuery() {
        String sql = "RETURN QUERY SELECT 1;";
        AstNode ast = new PlPgSqlParser(lex(sql), SqlDialect.POSTGRESQL).parseReturnStatement();
        assertEquals("RETURN_QUERY_STATEMENT", ast.getNodeType());
    }

    @Test
    void parseReturnNext() {
        AstNode ast = new PlPgSqlParser(lex("RETURN NEXT 42;"), SqlDialect.POSTGRESQL).parseReturnStatement();
        assertTrue("RETURN_NEXT_STATEMENT".equals(ast.getNodeType())
                        || ("RETURN_STATEMENT".equals(ast.getNodeType())
                            && findDeep(ast, "EXPRESSION") != null
                            && findDeep(ast, "EXPRESSION").getValue().toUpperCase().contains("NEXT")),
                ast.getNodeType());
    }

    @Test
    void parseRaise_withNotice() {
        String sql = "RAISE NOTICE 'msg';";
        AstNode ast = new PlPgSqlParser(lex(sql), SqlDialect.POSTGRESQL).parseRaiseStatement();
        assertEquals("RAISE_STATEMENT", ast.getNodeType());
        assertTrue(ast.getChildren().size() >= 1);
    }

    @Test
    void executeStatement_parses() {
        String sql = "EXECUTE 'SELECT 1';";
        AstNode ast = new PlPgSqlParser(lex("BEGIN " + sql + " END;"), SqlDialect.POSTGRESQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "EXECUTE_STATEMENT"));
    }

    @Test
    void getDiagnostics_parses() {
        String sql = "BEGIN GET DIAGNOSTICS x = ROW_COUNT; END;";
        AstNode ast = new PlPgSqlParser(lex(sql), SqlDialect.POSTGRESQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "GET_DIAGNOSTICS_STATEMENT"));
    }

    @Test
    void notify_listen_parses() {
        String sql = "BEGIN NOTIFY chan; LISTEN other; END;";
        AstNode ast = new PlPgSqlParser(lex(sql), SqlDialect.POSTGRESQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "NOTIFY_STATEMENT"));
        assertNotNull(findDeep(ast, "LISTEN_STATEMENT"));
    }

    @Test
    void declareInitializer_colonEquals() {
        String sql = "DECLARE\n  n int := 0;\nBEGIN\n  n := 1;\nEND;";
        AstNode ast = new PlPgSqlParser(lex(sql), SqlDialect.POSTGRESQL).parseBlock();
        assertNotNull(findDeep(ast, "DEFAULT_VALUE"));
    }

    @Test
    void nestedBlocks_pgStyle() {
        String sql = "BEGIN\n"
                + "  BEGIN\n"
                + "    BEGIN\n"
                + "      NULL;\n"
                + "    END;\n"
                + "  END;\n"
                + "END;";
        AstNode ast = new PlPgSqlParser(lex(sql), SqlDialect.POSTGRESQL).parseBlock();
        long blocks = 0;
        blocks = countType(ast, "BLOCK_STATEMENT");
        assertTrue(blocks >= 3);
    }

    private static int countType(AstNode n, String t) {
        int c = t.equals(n.getNodeType()) ? 1 : 0;
        for (AstNode ch : n.getChildren()) {
            c += countType(ch, t);
        }
        return c;
    }

    @Test
    void exceptionWhen_parses() {
        String sql = "BEGIN\n"
                + "  RAISE EXCEPTION 'x';\n"
                + "EXCEPTION\n"
                + "  WHEN OTHERS THEN\n"
                + "    RAISE NOTICE 'handled';\n"
                + "END;";
        AstNode ast = new PlPgSqlParser(lex(sql), SqlDialect.POSTGRESQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "EXCEPTION_BLOCK"));
    }

    @Test
    void parseFailure_notBeginOrDeclare() {
        assertThrows(SqlParser.ParseException.class,
                () -> new PlPgSqlParser(lex("SELECT 1;"), SqlDialect.POSTGRESQL).parseBlock());
    }

    @Test
    void procPg001_missingLanguage() {
        String sql = "CREATE FUNCTION pf001() RETURNS int AS $$\nBEGIN RETURN 1; END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-001");
    }

    @Test
    void procPg002_securityDefinerNoSearchPath() {
        String sql = "CREATE FUNCTION pf002() RETURNS int SECURITY DEFINER LANGUAGE plpgsql AS $$\nBEGIN RETURN 1; END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-002");
    }

    @Test
    void procPg003_stableWithDml() {
        String sql = "CREATE FUNCTION pf003() RETURNS void LANGUAGE plpgsql AS $$\nBEGIN INSERT INTO t VALUES (1); END;\n$$ STABLE;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-003");
    }

    @Test
    void procPg004_setofBareReturn() {
        String sql = "CREATE FUNCTION pf004() RETURNS SETOF int LANGUAGE plpgsql AS $$\nBEGIN RETURN; END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-004");
    }

    @Test
    void procPg005_triggerWithoutReturnNew() {
        String sql = "CREATE FUNCTION pf005() RETURNS trigger LANGUAGE plpgsql AS $$\nBEGIN NULL; END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-005");
    }

    @Test
    void procPg006_notifyInComplexBody() {
        String sql = "CREATE FUNCTION pf006() RETURNS void LANGUAGE plpgsql AS $$\n"
                + "BEGIN\n"
                + "  WHILE true LOOP NULL; END LOOP;\n"
                + "  FOR r IN SELECT 1 LOOP NULL; END LOOP;\n"
                + "  LOOP NULL; END LOOP;\n"
                + "  NOTIFY evt;\n"
                + "END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-006");
    }

    @Test
    void procPg007_executeConcatNoQuoteLiteral() {
        String sql = "CREATE FUNCTION pf007() RETURNS void LANGUAGE plpgsql AS $$\n"
                + "BEGIN EXECUTE 'SELECT ' || unsafe; END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-007");
    }

    @Test
    void procPg008_executeConcatNoUsing() {
        String sql = "CREATE FUNCTION pf008() RETURNS void LANGUAGE plpgsql AS $$\n"
                + "BEGIN EXECUTE 'SELECT ' || x; END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-008");
    }

    @Test
    void procPg009_getDiagnosticsUnused() {
        String sql = "CREATE FUNCTION pf009() RETURNS void LANGUAGE plpgsql AS $$\nBEGIN GET DIAGNOSTICS _cnt = ROW_COUNT; END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-009");
    }

    @Test
    void procPg010_whenOthersSilent() {
        String sql = "CREATE FUNCTION pf010() RETURNS void LANGUAGE plpgsql AS $$\n"
                + "BEGIN\n"
                + "  NULL;\n"
                + "EXCEPTION WHEN OTHERS THEN\n"
                + "  NULL;\n"
                + "END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        assertHasCode(new PlPgSqlAnalyzer(ast, sql).analyze(), "PROC-PG-010");
    }

    @Test
    void stress_deepNestedIf() {
        StringBuilder sb = new StringBuilder("BEGIN\n");
        for (int i = 0; i < 8; i++) {
            sb.append("  IF true THEN\n");
        }
        sb.append("    NULL;\n");
        for (int i = 0; i < 8; i++) {
            sb.append("  END IF;\n");
        }
        sb.append("END;");
        AstNode ast = new PlPgSqlParser(lex(sb.toString()), SqlDialect.POSTGRESQL).parseBlockFromBegin();
        assertTrue(countType(ast, "IF_STATEMENT") >= 8);
    }

    @Test
    void dollarQuote_splitMultipleStatements_mysqlSplitterDoesNotBreakBody() {
        String inner = "BEGIN\nPERFORM 1;\nPERFORM 2;\nEND";
        String sql = "SELECT " + "$$" + inner + "$$" + "::text;";
        List<String> stmts = StatementSplitter.split(sql, SqlDialect.POSTGRESQL);
        assertEquals(1, stmts.size());
    }

    @Test
    void forLoop_pgForm() {
        String sql = "BEGIN\n  FOR i IN 1 .. 3 LOOP\n    NULL;\n  END LOOP;\nEND;";
        AstNode ast = new PlPgSqlParser(lex(sql), SqlDialect.POSTGRESQL).parseBlockFromBegin();
        assertNotNull(findDeep(ast, "FOR_STATEMENT"));
    }

    @Test
    void mergeSemantic_pgAnalyzer() {
        String sql = "CREATE FUNCTION fm() RETURNS int LANGUAGE plpgsql AS $$\nDECLARE n int;\nBEGIN n := 1; RETURN 1; END;\n$$;";
        AstNode ast = new SqlParser(lex(sql), SqlDialect.POSTGRESQL).parse();
        List<SemanticError> sem = ProceduralSemanticAnalyzer.analyze(ast, sql);
        List<SemanticError> pg = new PlPgSqlAnalyzer(ast, sql).analyze();
        assertTrue(sem.stream().anyMatch(e -> "PROC-SEM-001".equals(e.code())));
        assertTrue(pg.stream().noneMatch(e -> "PROC-PG-001".equals(e.code())));
    }
}
