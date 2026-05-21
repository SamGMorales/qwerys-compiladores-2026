package com.qwerys.qwerys_backend.analyzer.nosql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link PainlessParser}: happy paths, {@link PainlessParser.ParseException}, and scale. */
class PainlessParserTest {

    private static PainlessAstNode parse(String src) throws Exception {
        List<PainlessToken> toks = new PainlessLexer(src).tokenize();
        return new PainlessParser(toks).parse();
    }

    @Test
    void emptyChunk() throws Exception {
        PainlessAstNode ast = parse("");
        assertEquals(PainlessAstNode.CHUNK, ast.kind());
        assertTrue(ast.children().isEmpty());
    }

    @Test
    void returnField() throws Exception {
        PainlessAstNode ast = parse("return doc['price'].value;");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }

    @Test
    void defAndAssign() throws Exception {
        PainlessAstNode ast = parse("def x = 1; return x;");
        assertTrue(ast.children().size() >= 2);
    }

    @Test
    void ifBraces() throws Exception {
        PainlessAstNode ast = parse("if (true) { return 1; }");
        assertEquals(PainlessAstNode.IF, ast.children().get(0).kind());
    }

    @Test
    void whileBlock() throws Exception {
        PainlessAstNode ast = parse("while (false) { def a = 1; }");
        assertEquals(PainlessAstNode.WHILE, ast.children().get(0).kind());
    }

    @Test
    void forLoopTrueConditionEmptyUpdate() throws Exception {
        PainlessAstNode ast = parse("for (def i = 0; true; ) { break; }");
        assertEquals(PainlessAstNode.FOR, ast.children().get(0).kind());
    }

    @Test
    void blockStatement() throws Exception {
        PainlessAstNode ast = parse("{ def z = 0; return z; }");
        assertEquals(PainlessAstNode.BLOCK, ast.children().get(0).kind());
    }

    @Test
    void methodCallChain() throws Exception {
        PainlessAstNode ast = parse("return doc['a'].value + doc['b'].value;");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }

    @Test
    void ctxSourceAssignExpr() throws Exception {
        PainlessAstNode ast = parse("ctx._source.counter = ctx._source.counter + params.inc;");
        assertEquals(PainlessAstNode.ASSIGNMENT, ast.children().get(0).kind());
    }

    @Test
    void newKeyword() throws Exception {
        PainlessAstNode ast = parse("def a = new ArrayList(); return a;");
        assertTrue(ast.children().size() >= 1);
    }

    @Test
    void unaryNot() throws Exception {
        PainlessAstNode ast = parse("return !false;");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }

    @Test
    void ternaryStyleViaIf() throws Exception {
        PainlessAstNode ast = parse("if (doc['x'].size() == 0) { return ''; } return doc['x'].value;");
        assertEquals(PainlessAstNode.IF, ast.children().get(0).kind());
    }

    @Test
    void missingSemicolonAfterReturn_throws() {
        assertThrows(PainlessParser.ParseException.class, () -> parse("return 1 def x = 2;"));
    }

    @Test
    void unexpectedBrace_throws() {
        assertThrows(PainlessParser.ParseException.class, () -> parse("}"));
    }

    @Test
    void defMissingName_throws() {
        assertThrows(PainlessParser.ParseException.class, () -> parse("def = 1;"));
    }

    @Test
    void whileMissingParen_throws() {
        assertThrows(PainlessParser.ParseException.class, () -> parse("while true { }"));
    }

    @Test
    void ifMissingParen_throws() {
        assertThrows(PainlessParser.ParseException.class, () -> parse("if true { }"));
    }

    @Test
    void deepNestedBlocks_parses() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("{ ");
        }
        sb.append("return 1;");
        for (int i = 0; i < 30; i++) {
            sb.append(" }");
        }
        assertDoesNotThrow(() -> parse(sb.toString()));
    }

    @Test
    void largeScript_manyDefs() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("def _").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("return _59;");
        PainlessAstNode ast = parse(sb.toString());
        assertTrue(ast.children().size() >= 60);
    }

    @Test
    void nestedIfElse_parses() throws Exception {
        String src = """
                if (doc['a'].size() != 0) {
                  if (doc['b'].size() != 0) {
                    return doc['a'].value + doc['b'].value;
                  }
                }
                return 0;
                """;
        assertDoesNotThrow(() -> parse(src));
    }

    @Test
    void forLoopEmptyUpdate() throws Exception {
        PainlessAstNode ast = parse("for (def i = 0; i < 10; ) { break; }");
        assertEquals(PainlessAstNode.FOR, ast.children().get(0).kind());
    }

    @Test
    void paramsAccess() throws Exception {
        PainlessAstNode ast = parse("return params['threshold'];");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }

    @Test
    void stringConcat() throws Exception {
        PainlessAstNode ast = parse("return 'a' + 'b';");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }

    @Test
    void doubleStringEscape() throws Exception {
        PainlessAstNode ast = parse("return \"line\\nbreak\";");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }

    @Test
    void booleanLiterals() throws Exception {
        PainlessAstNode ast = parse("return true && false;");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }

    @Test
    void comparisonChain() throws Exception {
        PainlessAstNode ast = parse("return doc['x'].value < 100;");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }

    @Test
    void longIdentifierChain() throws Exception {
        PainlessAstNode ast = parse("return doc['very_long_field_name_here'].value;");
        assertEquals(PainlessAstNode.RETURN, ast.children().get(0).kind());
    }
}
