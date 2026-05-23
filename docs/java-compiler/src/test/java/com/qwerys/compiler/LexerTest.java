package com.qwerys.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de pruebas para el Analizador Lexico
 * Responsable: Joshua Eduardo Garcia Reyes - Rama: feature/joshua-garcia-testing
 */
public class LexerTest {

    @Test
    @DisplayName("SELECT * FROM tabla - tokens correctos")
    void testSelectStar() {
        Lexer lexer = new Lexer("SELECT * FROM usuarios");
        List<Token> tokens = lexer.tokenize();
        assertEquals(TokenType.SELECT,     tokens.get(0).type);
        assertEquals(TokenType.ASTERISK,   tokens.get(1).type);
        assertEquals(TokenType.FROM,       tokens.get(2).type);
        assertEquals(TokenType.IDENTIFIER, tokens.get(3).type);
        assertEquals("usuarios",           tokens.get(3).value);
        assertEquals(TokenType.END_OF_FILE,tokens.get(4).type);
    }

    @Test
    @DisplayName("Operadores de comparacion reconocidos")
    void testOperators() {
        Lexer lexer = new Lexer("> >= < <= = !=");
        List<Token> tokens = lexer.tokenize();
        assertEquals(TokenType.GREATER,       tokens.get(0).type);
        assertEquals(TokenType.GREATER_EQUAL, tokens.get(1).type);
        assertEquals(TokenType.LESS,          tokens.get(2).type);
        assertEquals(TokenType.LESS_EQUAL,    tokens.get(3).type);
        assertEquals(TokenType.EQUAL,         tokens.get(4).type);
        assertEquals(TokenType.NOT_EQUAL,     tokens.get(5).type);
    }

    @Test
    @DisplayName("Numero reconocido correctamente")
    void testNumber() {
        Lexer lexer = new Lexer("18");
        List<Token> tokens = lexer.tokenize();
        assertEquals(TokenType.NUMBER, tokens.get(0).type);
        assertEquals("18", tokens.get(0).value);
    }

    @Test
    @DisplayName("String entre comillas reconocido")
    void testString() {
        Lexer lexer = new Lexer("'Guatemala'");
        List<Token> tokens = lexer.tokenize();
        assertEquals(TokenType.STRING, tokens.get(0).type);
        assertEquals("Guatemala", tokens.get(0).value);
    }

    @Test
    @DisplayName("Comentario SQL ignorado correctamente")
    void testComment() {
        Lexer lexer = new Lexer("SELECT * -- esto es comentario\nFROM usuarios");
        List<Token> tokens = lexer.tokenize();
        assertEquals(TokenType.SELECT, tokens.get(0).type);
        assertEquals(TokenType.ASTERISK, tokens.get(1).type);
        assertEquals(TokenType.FROM, tokens.get(2).type);
    }
}
