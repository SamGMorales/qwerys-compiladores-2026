package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;

class SqlLexerTest {

    @Test
    void tokenizesSimpleSelectQuery() {
        SqlLexer lexer = new SqlLexer("SELECT id FROM users WHERE id = 1");
        List<Token> tokens = lexer.tokenize();

        tokens.forEach(token ->
                System.out.printf("[%-12s] %-20s  line=%d  col=%d%n",
                        token.type(), token.value(), token.line(), token.column())
        );
    }
}
