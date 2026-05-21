package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lexer for DynamoDB Expression Syntax (KeyCondition, Condition, Filter, fragment of Update).
 *
 * <p>Recognizes {@code #name} placeholders, {@code :value} placeholders, function names,
 * logical operators, comparators, update clause headers, path operators, parentheses and commas.
 */
public final class DynamoDbExpressionLexer {

    public static final class LexException extends RuntimeException {
        private final int line;
        private final int column;
        private final String rawMessage;

        public LexException(String message, int line, int column) {
            super(message + " at line " + line + ", column " + column);
            this.line = line;
            this.column = column;
            this.rawMessage = message;
        }

        public int line() {
            return line;
        }

        public int column() {
            return column;
        }

        /** Lexer text without the trailing {@code at line …, column …} suffix. */
        public String detailMessage() {
            return rawMessage;
        }
    }

    private final String input;
    private int pos;
    private int line;
    private int column;

    public DynamoDbExpressionLexer(String input) {
        this.input = input != null ? input : "";
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    public List<DynamoDbExpressionToken> tokenize() {
        List<DynamoDbExpressionToken> out = new ArrayList<>(64);
        skipWs();
        while (!isEof()) {
            char c = peek();
            int tl = line;
            int tc = column;

            switch (c) {
                case '(' -> {
                    advance();
                    out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.LPAREN, "(", tl, tc));
                }
                case ')' -> {
                    advance();
                    out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.RPAREN, ")", tl, tc));
                }
                case '[' -> {
                    advance();
                    out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.LBRACKET, "[", tl, tc));
                }
                case ']' -> {
                    advance();
                    out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.RBRACKET, "]", tl, tc));
                }
                case ',' -> {
                    advance();
                    out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.COMMA, ",", tl, tc));
                }
                case '.' -> {
                    advance();
                    out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.DOT, ".", tl, tc));
                }
                case '=' -> {
                    advance();
                    out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.OPERATOR, "=", tl, tc));
                }
                case '<' -> {
                    advance();
                    if (peek() == '=') {
                        advance();
                        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.OPERATOR, "<=", tl, tc));
                    } else if (peek() == '>') {
                        advance();
                        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.OPERATOR, "<>", tl, tc));
                    } else {
                        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.OPERATOR, "<", tl, tc));
                    }
                }
                case '>' -> {
                    advance();
                    if (peek() == '=') {
                        advance();
                        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.OPERATOR, ">=", tl, tc));
                    } else {
                        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.OPERATOR, ">", tl, tc));
                    }
                }
                case '!' -> {
                    advance();
                    if (peek() == '=') {
                        advance();
                        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.OPERATOR, "!=", tl, tc));
                    } else {
                        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.UNKNOWN, "!", tl, tc));
                    }
                }
                case '#' -> out.add(readAttrName(tl, tc));
                case ':' -> out.add(readAttrValue(tl, tc));
                default -> {
                    if (Character.isDigit(c)) {
                        out.add(readNumber(tl, tc));
                    } else if (isIdentStart(c)) {
                        out.add(readWordOrKeyword(tl, tc));
                    } else {
                        advance();
                        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.UNKNOWN, String.valueOf(c), tl, tc));
                    }
                }
            }
            skipWs();
        }
        out.add(new DynamoDbExpressionToken(DynamoDbExpressionTokenType.EOF, "", line, column));
        return out;
    }

    private DynamoDbExpressionToken readAttrName(int tl, int tc) {
        advance(); // #
        if (isEof() || !isIdentBody(peek())) {
            throw new LexException("Unterminated attribute name placeholder", tl, tc);
        }
        String id = readIdentBody();
        return new DynamoDbExpressionToken(DynamoDbExpressionTokenType.ATTR_NAME, "#" + id, tl, tc);
    }

    private DynamoDbExpressionToken readAttrValue(int tl, int tc) {
        advance(); // :
        if (isEof() || !isIdentBody(peek())) {
            throw new LexException("Unterminated value placeholder", tl, tc);
        }
        String id = readIdentBody();
        return new DynamoDbExpressionToken(DynamoDbExpressionTokenType.ATTR_VALUE, ":" + id, tl, tc);
    }

    private DynamoDbExpressionToken readNumber(int tl, int tc) {
        int start = pos;
        while (!isEof() && Character.isDigit(peek())) {
            advance();
        }
        String n = input.substring(start, pos);
        return new DynamoDbExpressionToken(DynamoDbExpressionTokenType.NUMBER, n, tl, tc);
    }

    private DynamoDbExpressionToken readWordOrKeyword(int tl, int tc) {
        String w = readIdentBody();
        String u = w.toUpperCase(Locale.ROOT);
        DynamoDbExpressionTokenType t = switch (u) {
            case "AND" -> DynamoDbExpressionTokenType.AND;
            case "OR" -> DynamoDbExpressionTokenType.OR;
            case "NOT" -> DynamoDbExpressionTokenType.NOT;
            case "BETWEEN" -> DynamoDbExpressionTokenType.BETWEEN;
            case "IN" -> DynamoDbExpressionTokenType.IN;
            case "SET" -> DynamoDbExpressionTokenType.SET;
            case "REMOVE" -> DynamoDbExpressionTokenType.REMOVE;
            case "ADD" -> DynamoDbExpressionTokenType.ADD;
            case "DELETE" -> DynamoDbExpressionTokenType.DELETE;
            default -> DynamoDbExpressionTokenType.IDENTIFIER;
        };
        return new DynamoDbExpressionToken(t, w, tl, tc);
    }

    private String readIdentBody() {
        int start = pos;
        while (!isEof() && isIdentBody(peek())) {
            advance();
        }
        return input.substring(start, pos);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentBody(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void skipWs() {
        while (!isEof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                advance();
                line++;
                column = 1;
            } else {
                break;
            }
        }
    }

    private boolean isEof() {
        return pos >= input.length();
    }

    private char peek() {
        return input.charAt(pos);
    }

    private void advance() {
        if (!isEof()) {
            pos++;
            column++;
        }
    }
}
