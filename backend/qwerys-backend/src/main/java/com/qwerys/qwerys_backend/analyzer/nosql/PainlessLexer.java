package com.qwerys.qwerys_backend.analyzer.nosql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lexical analyzer for a Java-like Painless subset used in Elasticsearch scripts.
 */
public final class PainlessLexer {

    private static final Set<String> KEYWORDS = Set.of(
            "def", "if", "else", "while", "for", "return", "new", "params", "doc", "ctx");

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;

    public PainlessLexer(String source) {
        this.src = source == null ? "" : source;
    }

    public List<PainlessToken> tokenize() throws LexException {
        List<PainlessToken> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (isEof()) {
                out.add(new PainlessToken(PainlessTokenType.EOF, "", line, col));
                return out;
            }
            char c = peek();
            int tLine = line;
            int tCol = col;
            if (c == '\'' || c == '"') {
                out.add(readString(c, tLine, tCol));
            } else if (isDigit(c) || (c == '.' && pos + 1 < src.length() && isDigit(src.charAt(pos + 1)))) {
                out.add(readNumber(tLine, tCol));
            } else if (isIdentStart(c)) {
                String word = readIdent();
                PainlessTokenType ty = KEYWORDS.contains(word) ? PainlessTokenType.KEYWORD : PainlessTokenType.IDENTIFIER;
                out.add(new PainlessToken(ty, word, tLine, tCol));
            } else if (tryMultiCharOp(out, tLine, tCol)) {
                // emitted
            } else if (isSingleSymbol(c)) {
                advance();
                out.add(new PainlessToken(PainlessTokenType.SYMBOL, String.valueOf(c), tLine, tCol));
            } else if (isOperatorChar(c)) {
                advance();
                out.add(new PainlessToken(PainlessTokenType.OPERATOR, String.valueOf(c), tLine, tCol));
            } else {
                throw new LexException("Unexpected character '" + c + "'", line, col);
            }
        }
    }

    private boolean tryMultiCharOp(List<PainlessToken> out, int tLine, int tCol) {
        if (pos + 1 >= src.length()) {
            return false;
        }
        char a = peek();
        char b = src.charAt(pos + 1);
        String pair = "" + a + b;
        return switch (pair) {
            case "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=" -> {
                advance();
                advance();
                out.add(new PainlessToken(PainlessTokenType.OPERATOR, pair, tLine, tCol));
                yield true;
            }
            default -> false;
        };
    }

    private static boolean isSingleSymbol(char c) {
        return c == '(' || c == ')' || c == '{' || c == '}' || c == '[' || c == ']'
                || c == ',' || c == ';' || c == ':' || c == '.';
    }

    private static boolean isOperatorChar(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%'
                || c == '=' || c == '<' || c == '>' || c == '!' || c == '&' || c == '|' || c == '^' || c == '~'
                || c == '?';
    }

    private PainlessToken readString(char quote, int tLine, int tCol) throws LexException {
        advance();
        StringBuilder sb = new StringBuilder();
        while (!isEof()) {
            char ch = peek();
            if (ch == quote) {
                advance();
                return new PainlessToken(PainlessTokenType.STRING, sb.toString(), tLine, tCol);
            }
            if (ch == '\\' && pos + 1 < src.length()) {
                advance();
                char esc = peek();
                advance();
                sb.append(switch (esc) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\', '\'', '"' -> esc;
                    default -> esc;
                });
                continue;
            }
            if (ch == '\n' || ch == '\r') {
                throw new LexException("Unterminated string literal", tLine, tCol);
            }
            sb.append(ch);
            advance();
        }
        throw new LexException("Unterminated string literal", tLine, tCol);
    }

    private PainlessToken readNumber(int tLine, int tCol) throws LexException {
        int start = pos;
        if (peek() == '0' && pos + 1 < src.length() && (peekAhead(1) == 'x' || peekAhead(1) == 'X')) {
            advance();
            advance();
            while (!isEof() && isHex(peek())) {
                advance();
            }
        } else {
            while (!isEof() && isDigit(peek())) {
                advance();
            }
            if (peek() == '.' && pos + 1 < src.length() && isDigit(peekAhead(1))) {
                advance();
                while (!isEof() && isDigit(peek())) {
                    advance();
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                advance();
                if (peek() == '+' || peek() == '-') {
                    advance();
                }
                while (!isEof() && isDigit(peek())) {
                    advance();
                }
            }
        }
        String lex = src.substring(start, pos);
        if (lex.isEmpty() || lex.equals("0x") || lex.equals("0X")) {
            throw new LexException("Invalid number", tLine, tCol);
        }
        return new PainlessToken(PainlessTokenType.NUMBER, lex, tLine, tCol);
    }

    private char peekAhead(int delta) {
        int i = pos + delta;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private String readIdent() {
        int start = pos;
        while (!isEof() && isIdentPart(peek())) {
            advance();
        }
        return src.substring(start, pos);
    }

    private void skipWhitespaceAndComments() {
        while (!isEof()) {
            char c = peek();
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                advance();
            } else if (c == '/' && peekAhead(1) == '/') {
                advance();
                advance();
                while (!isEof() && peek() != '\n') {
                    advance();
                }
            } else if (c == '/' && peekAhead(1) == '*') {
                advance();
                advance();
                while (!isEof()) {
                    if (peek() == '*' && peekAhead(1) == '/') {
                        advance();
                        advance();
                        break;
                    }
                    advance();
                }
            } else {
                break;
            }
        }
    }

    private boolean isEof() {
        return pos >= src.length();
    }

    private char peek() {
        return isEof() ? '\0' : src.charAt(pos);
    }

    private void advance() {
        if (isEof()) {
            return;
        }
        char c = src.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    public static final class LexException extends Exception {
        private final int line;
        private final int column;

        public LexException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }

        public int line() {
            return line;
        }

        public int column() {
            return column;
        }
    }
}
