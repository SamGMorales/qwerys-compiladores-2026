package com.qwerys.compiler;

import java.util.ArrayList;
import java.util.List;

/**
 * FASE 2 - Analizador Lexico
 * Migrado de: Lexer.h + Lexer.cpp (C++)
 * Responsable: Juanita Raguex Tzum - Rama: feature/juanita-raguex-lexer
 */
public class Lexer {
    private String source;
    private int position;
    private int line;
    private int column;
    private char currentChar;

    public Lexer(String source) {
        this.source = source;
        this.position = 0;
        this.line = 1;
        this.column = 1;
        this.currentChar = source.isEmpty() ? '\0' : source.charAt(0);
    }

    private void advance() {
        if (currentChar == '\n') { line++; column = 1; }
        else { column++; }
        position++;
        currentChar = (position >= source.length()) ? '\0' : source.charAt(position);
    }

    private char peek() {
        int next = position + 1;
        return (next >= source.length()) ? '\0' : source.charAt(next);
    }

    private void skipWhitespace() {
        while (currentChar != '\0' && Character.isWhitespace(currentChar)) advance();
        if (currentChar == '-' && peek() == '-') {
            while (currentChar != '\0' && currentChar != '\n') advance();
            if (currentChar == '\n') advance();
            skipWhitespace();
        }
    }

    private TokenType keywordToType(String word) {
        switch (word.toUpperCase()) {
            case "SELECT": return TokenType.SELECT;
            case "FROM":   return TokenType.FROM;
            case "WHERE":  return TokenType.WHERE;
            default:       return TokenType.IDENTIFIER;
        }
    }

    private Token readIdentifierOrKeyword() {
        int sl = line, sc = column;
        StringBuilder sb = new StringBuilder();
        while (currentChar != '\0' && (Character.isLetterOrDigit(currentChar) || currentChar == '_')) {
            sb.append(currentChar); advance();
        }
        String val = sb.toString();
        return new Token(keywordToType(val), val, sl, sc);
    }

    private Token readNumber() {
        int sl = line, sc = column;
        StringBuilder sb = new StringBuilder();
        while (currentChar != '\0' && Character.isDigit(currentChar)) { sb.append(currentChar); advance(); }
        return new Token(TokenType.NUMBER, sb.toString(), sl, sc);
    }

    private Token readString() {
        int sl = line, sc = column;
        StringBuilder sb = new StringBuilder();
        advance(); // skip opening quote
        while (currentChar != '\0' && currentChar != '\'') { sb.append(currentChar); advance(); }
        if (currentChar == '\'') advance();
        return new Token(TokenType.STRING, sb.toString(), sl, sc);
    }

    public Token getNextToken() {
        skipWhitespace();
        if (currentChar == '\0') return new Token(TokenType.END_OF_FILE, "", line, column);
        int sl = line, sc = column;
        if (Character.isLetter(currentChar) || currentChar == '_') return readIdentifierOrKeyword();
        if (Character.isDigit(currentChar)) return readNumber();
        if (currentChar == '\'') return readString();
        if (currentChar == '>') { advance(); if (currentChar == '=') { advance(); return new Token(TokenType.GREATER_EQUAL, ">=", sl, sc); } return new Token(TokenType.GREATER, ">", sl, sc); }
        if (currentChar == '<') { advance(); if (currentChar == '=') { advance(); return new Token(TokenType.LESS_EQUAL, "<=", sl, sc); } return new Token(TokenType.LESS, "<", sl, sc); }
        if (currentChar == '!') { advance(); if (currentChar == '=') { advance(); return new Token(TokenType.NOT_EQUAL, "!=", sl, sc); } return new Token(TokenType.INVALID, "!", sl, sc); }
        char ch = currentChar; advance();
        switch (ch) {
            case '=': return new Token(TokenType.EQUAL, "=", sl, sc);
            case '*': return new Token(TokenType.ASTERISK, "*", sl, sc);
            case ',': return new Token(TokenType.COMMA, ",", sl, sc);
            case ';': return new Token(TokenType.SEMICOLON, ";", sl, sc);
            default:  return new Token(TokenType.INVALID, String.valueOf(ch), sl, sc);
        }
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do { t = getNextToken(); tokens.add(t); } while (t.type != TokenType.END_OF_FILE);
        return tokens;
    }
}
