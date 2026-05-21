package com.qwerys.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de pruebas para el Analizador Sintactico
 * Responsable: Joshua Eduardo Garcia Reyes - Rama: feature/joshua-garcia-testing
 */
public class ParserTest {

    private SelectNode parseQuery(String sql) {
        Lexer lexer = new Lexer(sql);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser(tokens);
        return parser.parse();
    }

    @Test
    @DisplayName("SELECT * FROM tabla - AST correcto")
    void testSelectStar() {
        SelectNode ast = parseQuery("SELECT * FROM usuarios");
        assertTrue(ast.selectAll);
        assertEquals("usuarios", ast.tableName);
        assertNull(ast.whereCondition);
    }

    @Test
    @DisplayName("SELECT columnas FROM tabla - lista de columnas")
    void testSelectColumns() {
        SelectNode ast = parseQuery("SELECT nombre, edad FROM usuarios");
        assertFalse(ast.selectAll);
        assertEquals(2, ast.columns.size());
        assertEquals("nombre", ast.columns.get(0));
        assertEquals("edad", ast.columns.get(1));
    }

    @Test
    @DisplayName("WHERE condition parseada correctamente")
    void testWhereCondition() {
        SelectNode ast = parseQuery("SELECT * FROM usuarios WHERE edad > 18");
        assertNotNull(ast.whereCondition);
        assertEquals(CompOperator.GREATER, ast.whereCondition.op);
    }

    @Test
    @DisplayName("Error de sintaxis lanzado correctamente")
    void testSyntaxError() {
        assertThrows(RuntimeException.class, () -> parseQuery("FROM usuarios SELECT *"));
    }
}
