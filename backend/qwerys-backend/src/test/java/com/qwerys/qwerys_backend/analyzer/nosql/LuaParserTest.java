package com.qwerys.qwerys_backend.analyzer.nosql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link LuaParser}: valid Lua subset, parse failures, and stress shapes. */
class LuaParserTest {

    private static LuaAstNode parse(String src) throws Exception {
        List<LuaToken> toks = new LuaLexer(src).tokenize();
        return new LuaParser(toks).parse();
    }

    @Test
    void emptyChunk() throws Exception {
        LuaAstNode ast = parse("");
        assertEquals(LuaAstNode.CHUNK, ast.kind());
        assertTrue(ast.children().isEmpty());
    }

    @Test
    void returnNumber() throws Exception {
        LuaAstNode ast = parse("return 42");
        assertEquals(1, ast.children().size());
        assertEquals(LuaAstNode.RETURN_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void localDeclNoInit() throws Exception {
        LuaAstNode ast = parse("local x");
        assertEquals(LuaAstNode.LOCAL_DECL, ast.children().get(0).kind());
    }

    @Test
    void globalAssignment() throws Exception {
        LuaAstNode ast = parse("x = 1");
        assertEquals(LuaAstNode.ASSIGNMENT, ast.children().get(0).kind());
    }

    @Test
    void ifThenEnd() throws Exception {
        LuaAstNode ast = parse("if true then return 1 end");
        assertEquals(LuaAstNode.IF_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void ifElseEnd() throws Exception {
        LuaAstNode ast = parse("if false then return 1 else return 2 end");
        assertEquals(LuaAstNode.IF_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void whileDoEnd() throws Exception {
        LuaAstNode ast = parse("while false do end");
        assertEquals(LuaAstNode.WHILE_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void repeatUntil() throws Exception {
        LuaAstNode ast = parse("repeat until true");
        assertEquals(LuaAstNode.REPEAT_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void genericForOnly() throws Exception {
        LuaAstNode ast = parse("for k, v in pairs(t) do end");
        assertEquals(LuaAstNode.FOR_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void functionDef() throws Exception {
        LuaAstNode ast = parse("function f() return 0 end");
        assertEquals(LuaAstNode.FUNCTION_DEF, ast.children().get(0).kind());
    }

    @Test
    void localFunction() throws Exception {
        LuaAstNode ast = parse("local function g() end");
        assertEquals(LuaAstNode.FUNCTION_DEF, ast.children().get(0).kind());
        assertTrue(ast.children().get(0).localAssignment());
    }

    @Test
    void redisCallExpr() throws Exception {
        LuaAstNode ast = parse("return redis.call('GET', KEYS[1])");
        assertEquals(LuaAstNode.RETURN_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void tableConstructorViaAssignment() throws Exception {
        LuaAstNode ast = parse("t = { 1, 2, 3 }");
        assertEquals(LuaAstNode.ASSIGNMENT, ast.children().get(0).kind());
    }

    @Test
    void semicolonSeparatedAssignments() throws Exception {
        LuaAstNode ast = parse("a = 1; b = 2; return a + b");
        assertTrue(ast.children().size() >= 3);
    }

    @Test
    void unexpectedEnd_throws() {
        assertThrows(LuaParser.ParseException.class, () -> parse("if true then"));
    }

    @Test
    void missingThen_throws() {
        assertThrows(LuaParser.ParseException.class, () -> parse("if true return 1 end"));
    }

    @Test
    void bareElse_throws() {
        assertThrows(LuaParser.ParseException.class, () -> parse("else return 1 end"));
    }

    @Test
    void unexpectedToken_throws() {
        assertThrows(Exception.class, () -> parse("@"));
    }

    @Test
    void whileMissingDo_throws() {
        assertThrows(LuaParser.ParseException.class, () -> parse("while true end"));
    }

    @Test
    void forMissingIn_throws() {
        assertThrows(LuaParser.ParseException.class, () -> parse("for k pairs(t) do end"));
    }

    @Test
    void deepNestedIf_parses() throws Exception {
        StringBuilder sb = new StringBuilder("if true then\n");
        for (int i = 0; i < 25; i++) {
            sb.append("if true then\n");
        }
        sb.append("return 1\n");
        for (int i = 0; i < 26; i++) {
            sb.append("end\n");
        }
        assertDoesNotThrow(() -> parse(sb.toString()));
    }

    @Test
    void largeScript_manyAssignments() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            sb.append("_").append(i).append(" = ").append(i).append("\n");
        }
        sb.append("return _79");
        LuaAstNode ast = parse(sb.toString());
        assertTrue(ast.children().size() >= 80);
    }

    @Test
    void longStringLiteral() throws Exception {
        String big = "x".repeat(800);
        LuaAstNode ast = parse("return '" + big + "'");
        assertEquals(LuaAstNode.RETURN_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void breakStatement() throws Exception {
        LuaAstNode ast = parse("while true do break end");
        LuaAstNode wh = ast.children().get(0);
        assertEquals(LuaAstNode.WHILE_STATEMENT, wh.kind());
        assertTrue(wh.children().get(1).children().stream().anyMatch(c -> LuaAstNode.BREAK_STATEMENT.equals(c.kind())));
    }

    @Test
    void elseifChain_parses() throws Exception {
        LuaAstNode ast = parse("if false then return 1 elseif false then return 2 else return 3 end");
        assertEquals(LuaAstNode.IF_STATEMENT, ast.children().get(0).kind());
    }

    @Test
    void exprStmtParenthesized() throws Exception {
        LuaAstNode ast = parse("(1 + 2)");
        assertEquals(LuaAstNode.EXPR_STMT, ast.children().get(0).kind());
    }

    @Test
    void multiplicativePrecedence() throws Exception {
        LuaAstNode ast = parse("return 1 + 2 * 3");
        assertEquals(LuaAstNode.RETURN_STATEMENT, ast.children().get(0).kind());
    }
}
