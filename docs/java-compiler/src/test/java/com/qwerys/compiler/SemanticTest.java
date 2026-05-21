package com.qwerys.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de pruebas para el Analizador Semantico
 * Responsable: Joshua Eduardo Garcia Reyes - Rama: feature/joshua-garcia-testing
 */
public class SemanticTest {

    private boolean analyze(String sql) {
        Lexer lexer = new Lexer(sql);
        List<Token> tokens = lexer.tokenize();
        Parser parser = new Parser(tokens);
        SelectNode ast = parser.parse();
        SymbolTable schema = new SymbolTable();
        SemanticAnalyzer analyzer = new SemanticAnalyzer(schema);
        return analyzer.analyze(ast);
    }

    @Test
    @DisplayName("Query valida - tabla y columnas existen")
    void testValidQuery() {
        assertTrue(analyze("SELECT nombre FROM usuarios"));
    }

    @Test
    @DisplayName("Error - tabla no existe")
    void testInvalidTable() {
        assertFalse(analyze("SELECT * FROM tabla_inexistente"));
    }

    @Test
    @DisplayName("Error - columna no existe")
    void testInvalidColumn() {
        assertFalse(analyze("SELECT columna_falsa FROM usuarios"));
    }

    @Test
    @DisplayName("SELECT * siempre valido si la tabla existe")
    void testSelectStar() {
        assertTrue(analyze("SELECT * FROM productos"));
    }

    @Test
    @DisplayName("WHERE con condicion valida")
    void testValidWhere() {
        assertTrue(analyze("SELECT * FROM usuarios WHERE edad > 18"));
    }
}
