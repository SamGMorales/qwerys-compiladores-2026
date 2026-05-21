package com.qwerys.qwerys_backend.analyzer.nosql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lexical analyzer for a Lua 5.1–shaped subset used in Redis {@code EVAL} scripts.
 */
public final class LuaLexer {

    private static final Set<String> KEYWORDS = Set.of(
            "function", "if", "then", "end", "else", "elseif",
            "while", "do", "repeat", "until", "for", "in", "break",
            "return", "local", "nil", "true", "false", "and", "or", "not");

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;

    public LuaLexer(String source) {
        this.src = source == null ? "" : source;
    }

    public List<LuaToken> tokenize() throws LexException {
        List<LuaToken> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (isEof()) {
                out.add(new LuaToken(LuaTokenType.EOF, "", line, col));
                return out;
            }
            char c = peek();
            int tLine = line;
            int tCol = col;
            if (c == '\'' || c == '"') {
                out.add(readString(c, tLine, tCol));
            } else if (c == '[' && longBracketEqualsCount() >= 0) {
                out.add(readLongBracketString(false, tLine, tCol));
            } else if (isDigit(c) || (c == '.' && pos + 1 < src.length() && isDigit(src.charAt(pos + 1)))) {
                out.add(readNumber(tLine, tCol));
            } else if (isIdentStart(c)) {
                String word = readIdent();
                LuaTokenType ty = KEYWORDS.contains(word) ? LuaTokenType.KEYWORD : LuaTokenType.IDENTIFIER;
                out.add(new LuaToken(ty, word, tLine, tCol));
            } else if (tryTwoCharOp(out, tLine, tCol)) {
                // emitted
            } else if (isSingleSymbol(c)) {
                advance();
                out.add(new LuaToken(LuaTokenType.SYMBOL, String.valueOf(c), tLine, tCol));
            } else if (isOperatorChar(c)) {
                advance();
                out.add(new LuaToken(LuaTokenType.OPERATOR, String.valueOf(c), tLine, tCol));
            } else {
                throw new LexException("Unexpected character '" + c + "'", line, col);
            }
        }
    }

    /** {@code -1} if not a long bracket; else number of {@code =} between brackets. */
    private int longBracketEqualsCount() {
        return longBracketEqualsCountFrom(pos);
    }

    private boolean tryTwoCharOp(List<LuaToken> out, int tLine, int tCol) {
        if (pos + 1 >= src.length()) {
            return false;
        }
        char a = peek();
        char b = src.charAt(pos + 1);
        String pair = "" + a + b;
        return switch (pair) {
            case "==", "~=", "<=", ">=", "..", "//", "<<", ">>" -> {
                advance();
                advance();
                out.add(new LuaToken(LuaTokenType.OPERATOR, pair, tLine, tCol));
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
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '^'
                || c == '#' || c == '=' || c == '<' || c == '>' || c == '~';
    }

    private LuaToken readString(char quote, int tLine, int tCol) throws LexException {
        advance(); // opening quote
        StringBuilder sb = new StringBuilder();
        while (!isEof()) {
            char ch = peek();
            if (ch == quote) {
                advance();
                return new LuaToken(LuaTokenType.STRING, sb.toString(), tLine, tCol);
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

    /**
     * Reads {@code [=*[ ... ]=*]} ; if {@code forComment}, opener must be {@code --} already consumed
     * and current char is {@code [}.
     */
    private LuaToken readLongBracketString(boolean forComment, int tLine, int tCol) throws LexException {
        advance(); // [
        int eq = 0;
        while (peek() == '=') {
            eq++;
            advance();
        }
        if (peek() != '[') {
            throw new LexException("Invalid long bracket", tLine, tCol);
        }
        advance(); // inner [
        if (peek() == '\n') {
            advance();
        }
        StringBuilder body = forComment ? null : new StringBuilder();
        while (!isEof()) {
            if (peek() == ']') {
                int closeEq = 0;
                int scan = pos + 1;
                while (scan < src.length() && src.charAt(scan) == '=') {
                    closeEq++;
                    scan++;
                }
                if (closeEq == eq && scan < src.length() && src.charAt(scan) == ']') {
                    advance(); // ]
                    for (int i = 0; i < eq; i++) {
                        advance();
                    }
                    advance(); // ]
                    return new LuaToken(LuaTokenType.STRING, body == null ? "" : body.toString(), tLine, tCol);
                }
            }
            if (body != null) {
                body.append(peek());
            }
            advance();
        }
        throw new LexException("Unterminated long bracket", tLine, tCol);
    }

    private LuaToken readNumber(int tLine, int tCol) throws LexException {
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
        return new LuaToken(LuaTokenType.NUMBER, lex, tLine, tCol);
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
            } else if (c == '-' && peekAhead(1) == '-') {
                int saveLine = line;
                int saveCol = col;
                advance();
                advance();
                if (peek() == '[') {
                    int eqc = longBracketEqualsCountFrom(pos);
                    if (eqc >= 0) {
                        try {
                            readLongBracketString(true, saveLine, saveCol);
                        } catch (LexException e) {
                            // skip to EOL on broken comment
                            while (!isEof() && peek() != '\n') {
                                advance();
                            }
                        }
                        continue;
                    }
                }
                while (!isEof() && peek() != '\n') {
                    advance();
                }
            } else {
                break;
            }
        }
    }

    private int longBracketEqualsCountFrom(int from) {
        if (from >= src.length() || src.charAt(from) != '[') {
            return -1;
        }
        if (from + 1 >= src.length()) {
            return -1;
        }
        char c1 = src.charAt(from + 1);
        if (c1 == '[') {
            return 0;
        }
        if (c1 != '=') {
            return -1;
        }
        int eq = 0;
        int i = from + 1;
        while (i < src.length() && src.charAt(i) == '=') {
            eq++;
            i++;
        }
        if (i < src.length() && src.charAt(i) == '[') {
            return eq;
        }
        return -1;
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
