package com.qwerys.compiler;

/**
 * Representa un token individual del analizador lexico
 * Migrado de: struct Token en Token.h (C++)
 * Responsable: Marjorie Samantha Giron Morales (Arquitecto)
 */
public class Token {
    public TokenType type;
    public String value;
    public int line;
    public int column;

    public Token() {
        this.type = TokenType.INVALID;
        this.value = "";
        this.line = 0;
        this.column = 0;
    }

    public Token(TokenType type, String value, int line, int column) {
        this.type = type;
        this.value = value;
        this.line = line;
        this.column = column;
    }

    public void print() {
        System.out.print("[" + type + "] ");
        if (!value.isEmpty()) System.out.print("'" + value + "' ");
        System.out.print("(L" + line + ":C" + column + ")");
    }
}
