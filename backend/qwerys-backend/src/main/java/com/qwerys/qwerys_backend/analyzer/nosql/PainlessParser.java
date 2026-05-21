package com.qwerys.qwerys_backend.analyzer.nosql;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for a Painless subset (Java-like braces and semicolons).
 */
public final class PainlessParser {

    private final List<PainlessToken> tokens;
    private int idx;

    public PainlessParser(List<PainlessToken> tokens) {
        this.tokens = tokens != null ? tokens : List.of();
        this.idx = 0;
    }

    public PainlessAstNode parse() throws ParseException {
        PainlessAstNode chunk = new PainlessAstNode(PainlessAstNode.CHUNK, 1);
        while (!isEof()) {
            skipSemicolons();
            if (isEof()) {
                break;
            }
            PainlessAstNode st = parseStatement();
            if (st != null) {
                chunk.addChild(st);
            }
        }
        return chunk;
    }

    private PainlessAstNode parseStatement() throws ParseException {
        PainlessToken t = cur();
        if (t.type() == PainlessTokenType.EOF) {
            return null;
        }
        if (t.type() == PainlessTokenType.KEYWORD) {
            if ("new".equals(t.lexeme()) || isIdentKeyword(t.lexeme())) {
                return parseAssignmentOrCallOrExprStmt();
            }
            return switch (t.lexeme()) {
                case "if" -> parseIf();
                case "while" -> parseWhile();
                case "for" -> parseFor();
                case "return" -> parseReturn();
                case "def" -> parseDef();
                default -> throw new ParseException("Unexpected keyword '" + t.lexeme() + "'", t.line(), t.column());
            };
        }
        if (t.type() == PainlessTokenType.SYMBOL && ";".equals(t.lexeme())) {
            advance();
            return null;
        }
        if (t.type() == PainlessTokenType.SYMBOL && "{".equals(t.lexeme())) {
            return parseBlockStatement();
        }
        if (t.type() == PainlessTokenType.IDENTIFIER) {
            return parseAssignmentOrCallOrExprStmt();
        }
        if (t.type() == PainlessTokenType.SYMBOL && "(".equals(t.lexeme())) {
            PainlessAstNode e = parseExpr(0);
            PainlessAstNode wrap = new PainlessAstNode(PainlessAstNode.EXPR_STMT, e.line());
            wrap.addChild(e);
            expectSemicolonOrEof();
            return wrap;
        }
        throw new ParseException("Unexpected token " + t.lexeme(), t.line(), t.column());
    }

    private PainlessAstNode parseBlockStatement() throws ParseException {
        return parseBlock();
    }

    private PainlessAstNode parseDef() throws ParseException {
        int ln = cur().line();
        expectKeyword("def");
        PainlessAstNode name = expectName();
        if (consumeSymbol('(')) {
            PainlessAstNode fn = new PainlessAstNode(PainlessAstNode.FUNCTION_DEF, ln);
            fn.setText(name.text());
            fn.addChild(name);
            if (!consumeSymbol(')')) {
                do {
                    fn.addChild(expectName());
                } while (consumeSymbol(','));
                expectSymbol(')');
            }
            fn.addChild(parseBlock());
            return fn;
        }
        PainlessAstNode a = new PainlessAstNode(PainlessAstNode.ASSIGNMENT, ln);
        a.setText("def");
        a.addChild(name);
        if (consumeOperator("=")) {
            a.addChild(parseExpr(0));
        }
        expectSemicolonOrEof();
        return a;
    }

    private PainlessAstNode parseIf() throws ParseException {
        int ln = cur().line();
        expectKeyword("if");
        expectSymbol('(');
        PainlessAstNode cond = parseExpr(0);
        expectSymbol(')');
        PainlessAstNode thenSt = parseStatementOrBlock();
        PainlessAstNode n = new PainlessAstNode(PainlessAstNode.IF, ln);
        n.addChild(cond);
        n.addChild(thenSt);
        if (isKeyword("else")) {
            advance();
            n.addChild(parseStatementOrBlock());
        }
        return n;
    }

    private PainlessAstNode parseWhile() throws ParseException {
        int ln = cur().line();
        expectKeyword("while");
        expectSymbol('(');
        PainlessAstNode cond = parseExpr(0);
        expectSymbol(')');
        PainlessAstNode body = parseStatementOrBlock();
        PainlessAstNode w = new PainlessAstNode(PainlessAstNode.WHILE, ln);
        w.addChild(cond);
        w.addChild(body);
        return w;
    }

    private PainlessAstNode parseFor() throws ParseException {
        int ln = cur().line();
        expectKeyword("for");
        expectSymbol('(');
        PainlessAstNode init = null;
        if (!consumeSymbol(';')) {
            init = parseForInitExpr();
            expectSymbol(';');
        }
        PainlessAstNode cond = null;
        if (!consumeSymbol(';')) {
            cond = parseExpr(0);
            expectSymbol(';');
        }
        PainlessAstNode update = null;
        if (!consumeSymbol(')')) {
            update = parseExpr(0);
            expectSymbol(')');
        }
        PainlessAstNode body = parseStatementOrBlock();
        PainlessAstNode f = new PainlessAstNode(PainlessAstNode.FOR, ln);
        if (init != null) {
            f.addChild(init);
        }
        if (cond != null) {
            f.addChild(cond);
        }
        if (update != null) {
            f.addChild(update);
        }
        f.addChild(body);
        return f;
    }

    private PainlessAstNode parseForInitExpr() throws ParseException {
        if (isKeyword("def")) {
            advance();
            PainlessAstNode name = expectName();
            PainlessAstNode a = new PainlessAstNode(PainlessAstNode.ASSIGNMENT, name.line());
            a.setText("def");
            a.addChild(name);
            if (consumeOperator("=")) {
                a.addChild(parseExpr(0));
            }
            return a;
        }
        PainlessAstNode lhs = parseExpr(0);
        if (consumeOperator("=")) {
            PainlessAstNode a = new PainlessAstNode(PainlessAstNode.ASSIGNMENT, lhs.line());
            a.addChild(lhs);
            a.addChild(parseExpr(0));
            return a;
        }
        return lhs;
    }

    private PainlessAstNode parseReturn() throws ParseException {
        int ln = cur().line();
        expectKeyword("return");
        PainlessAstNode r = new PainlessAstNode(PainlessAstNode.RETURN, ln);
        if (!(cur().type() == PainlessTokenType.SYMBOL && ";".equals(cur().lexeme())) && !isEof()) {
            r.addChild(parseExpr(0));
        }
        expectSemicolonOrEof();
        return r;
    }

    private PainlessAstNode parseStatementOrBlock() throws ParseException {
        if (cur().type() == PainlessTokenType.SYMBOL && "{".equals(cur().lexeme())) {
            return parseBlock();
        }
        PainlessAstNode st = parseStatement();
        return st != null ? st : new PainlessAstNode(PainlessAstNode.BLOCK, cur().line());
    }

    private PainlessAstNode parseBlock() throws ParseException {
        int ln = cur().line();
        expectSymbol('{');
        PainlessAstNode block = new PainlessAstNode(PainlessAstNode.BLOCK, ln);
        while (!isEof()) {
            if (cur().type() == PainlessTokenType.SYMBOL && "}".equals(cur().lexeme())) {
                break;
            }
            skipSemicolons();
            if (cur().type() == PainlessTokenType.SYMBOL && "}".equals(cur().lexeme())) {
                break;
            }
            PainlessAstNode st = parseStatement();
            if (st != null) {
                block.addChild(st);
            }
        }
        expectSymbol('}');
        return block;
    }

    private PainlessAstNode parseAssignmentOrCallOrExprStmt() throws ParseException {
        PainlessAstNode lhs = parseExpr(0);
        if (consumeOperator("=")) {
            PainlessAstNode rhs = parseExpr(0);
            PainlessAstNode a = new PainlessAstNode(PainlessAstNode.ASSIGNMENT, lhs.line());
            a.addChild(lhs);
            a.addChild(rhs);
            expectSemicolonOrEof();
            return a;
        }
        PainlessAstNode ex = new PainlessAstNode(PainlessAstNode.EXPR_STMT, lhs.line());
        ex.addChild(lhs);
        expectSemicolonOrEof();
        return ex;
    }

    private void expectSemicolonOrEof() throws ParseException {
        if (isEof()) {
            return;
        }
        if (cur().type() == PainlessTokenType.SYMBOL && ";".equals(cur().lexeme())) {
            advance();
            return;
        }
        if (cur().type() == PainlessTokenType.SYMBOL && "}".equals(cur().lexeme())) {
            return;
        }
        if (isKeyword("else")) {
            return;
        }
        throw new ParseException("Expected ';'", cur().line(), cur().column());
    }

    private PainlessAstNode parseExpr(int minPrec) throws ParseException {
        PainlessAstNode left = parseUnary();
        while (true) {
            PainlessToken op = cur();
            String lex = null;
            if (op.type() == PainlessTokenType.OPERATOR) {
                lex = op.lexeme();
            }
            if (lex == null) {
                break;
            }
            int p = binaryPrecedence(lex);
            if (p < 0) {
                break;
            }
            if (p < minPrec) {
                break;
            }
            int nextMin = "^".equals(lex) ? p : p + 1;
            advance();
            PainlessAstNode right = parseExpr(nextMin);
            PainlessAstNode bin = new PainlessAstNode(PainlessAstNode.BINARY_EXPR, left.line());
            bin.setText(lex);
            bin.addChild(left);
            bin.addChild(right);
            left = bin;
        }
        return left;
    }

    private int binaryPrecedence(String op) {
        return switch (op) {
            case "||" -> 1;
            case "&&" -> 2;
            case "==", "!=" -> 3;
            case "<", ">", "<=", ">=" -> 4;
            case "+", "-" -> 5;
            case "*", "/", "%" -> 6;
            default -> -1;
        };
    }

    private PainlessAstNode parseUnary() throws ParseException {
        PainlessToken t = cur();
        if (t.type() == PainlessTokenType.OPERATOR && ("!".equals(t.lexeme()) || "-".equals(t.lexeme()) || "+".equals(t.lexeme()))) {
            advance();
            PainlessAstNode u = new PainlessAstNode(PainlessAstNode.UNARY_EXPR, t.line());
            u.setText(t.lexeme());
            u.addChild(parseUnary());
            return u;
        }
        if (isKeyword("new")) {
            advance();
            PainlessAstNode ctor = expectName();
            PainlessAstNode n = new PainlessAstNode(PainlessAstNode.METHOD_CALL, ctor.line());
            n.setText("new");
            n.addChild(ctor);
            expectSymbol('(');
            if (!consumeSymbol(')')) {
                do {
                    n.addChild(parseExpr(0));
                } while (consumeSymbol(','));
                expectSymbol(')');
            }
            return n;
        }
        return parseSuffixedExpr();
    }

    private PainlessAstNode parseSuffixedExpr() throws ParseException {
        PainlessAstNode base = parsePrimary();
        while (true) {
            PainlessToken t = cur();
            if (t.type() == PainlessTokenType.SYMBOL && "(".equals(t.lexeme())) {
                base = finishCall(base);
            } else if (t.type() == PainlessTokenType.SYMBOL && "[".equals(t.lexeme())) {
                advance();
                PainlessAstNode idx = parseExpr(0);
                expectSymbol(']');
                PainlessAstNode n = new PainlessAstNode(PainlessAstNode.INDEX, base.line());
                n.addChild(base);
                n.addChild(idx);
                base = n;
            } else if (t.type() == PainlessTokenType.SYMBOL && ".".equals(t.lexeme())) {
                advance();
                PainlessAstNode fld = expectName();
                PainlessAstNode n = new PainlessAstNode(PainlessAstNode.FIELD, base.line());
                n.addChild(base);
                n.addChild(fld);
                base = n;
            } else {
                break;
            }
        }
        return base;
    }

    private PainlessAstNode finishCall(PainlessAstNode func) throws ParseException {
        expectSymbol('(');
        List<PainlessAstNode> args = new ArrayList<>();
        if (!consumeSymbol(')')) {
            args.add(parseExpr(0));
            while (consumeSymbol(',')) {
                args.add(parseExpr(0));
            }
            expectSymbol(')');
        }
        PainlessAstNode call = new PainlessAstNode(PainlessAstNode.METHOD_CALL, func.line());
        call.addChild(func);
        for (PainlessAstNode a : args) {
            call.addChild(a);
        }
        return call;
    }

    private PainlessAstNode parsePrimary() throws ParseException {
        PainlessToken t = cur();
        if (t.type() == PainlessTokenType.NUMBER || t.type() == PainlessTokenType.STRING) {
            advance();
            PainlessAstNode lit = new PainlessAstNode(PainlessAstNode.LITERAL, t.line());
            lit.setText(t.lexeme());
            return lit;
        }
        if (t.type() == PainlessTokenType.IDENTIFIER
                || (t.type() == PainlessTokenType.KEYWORD && isIdentKeyword(t.lexeme()))) {
            advance();
            PainlessAstNode n = new PainlessAstNode(PainlessAstNode.NAME, t.line());
            n.setText(t.lexeme());
            return n;
        }
        if (t.type() == PainlessTokenType.SYMBOL && "(".equals(t.lexeme())) {
            advance();
            PainlessAstNode inner = parseExpr(0);
            expectSymbol(')');
            return inner;
        }
        throw new ParseException("Unexpected token in expression: " + t.lexeme(), t.line(), t.column());
    }

    private static boolean isIdentKeyword(String lex) {
        return "params".equals(lex) || "doc".equals(lex) || "ctx".equals(lex);
    }

    private PainlessAstNode expectName() throws ParseException {
        PainlessToken t = cur();
        if (t.type() != PainlessTokenType.IDENTIFIER) {
            throw new ParseException("Expected name", t.line(), t.column());
        }
        advance();
        PainlessAstNode n = new PainlessAstNode(PainlessAstNode.NAME, t.line());
        n.setText(t.lexeme());
        return n;
    }

    private void expectSymbol(char c) throws ParseException {
        PainlessToken t = cur();
        if (t.type() != PainlessTokenType.SYMBOL || !String.valueOf(c).equals(t.lexeme())) {
            throw new ParseException("Expected '" + c + "'", t.line(), t.column());
        }
        advance();
    }

    private void expectKeyword(String kw) throws ParseException {
        PainlessToken t = cur();
        if (t.type() != PainlessTokenType.KEYWORD || !kw.equals(t.lexeme())) {
            throw new ParseException("Expected '" + kw + "'", t.line(), t.column());
        }
        advance();
    }

    private boolean consumeSymbol(char c) {
        if (cur().type() == PainlessTokenType.SYMBOL && String.valueOf(c).equals(cur().lexeme())) {
            advance();
            return true;
        }
        return false;
    }

    private boolean consumeOperator(String op) {
        if (cur().type() == PainlessTokenType.OPERATOR && op.equals(cur().lexeme())) {
            advance();
            return true;
        }
        return false;
    }

    private boolean isKeyword(String kw) {
        return cur().type() == PainlessTokenType.KEYWORD && kw.equals(cur().lexeme());
    }

    private void skipSemicolons() {
        while (cur().type() == PainlessTokenType.SYMBOL && ";".equals(cur().lexeme())) {
            advance();
        }
    }

    private PainlessToken cur() {
        if (idx >= tokens.size()) {
            return new PainlessToken(PainlessTokenType.EOF, "", lineLast(), 1);
        }
        return tokens.get(idx);
    }

    private int lineLast() {
        return tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).line();
    }

    private void advance() {
        if (idx < tokens.size()) {
            idx++;
        }
    }

    private boolean isEof() {
        return cur().type() == PainlessTokenType.EOF;
    }

    public static final class ParseException extends Exception {
        private final int line;
        private final int column;

        public ParseException(String message, int line, int column) {
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
