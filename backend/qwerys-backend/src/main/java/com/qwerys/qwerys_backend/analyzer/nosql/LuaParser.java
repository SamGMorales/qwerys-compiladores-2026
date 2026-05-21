package com.qwerys.qwerys_backend.analyzer.nosql;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser for a Lua subset used in Redis scripts.
 */
public final class LuaParser {

    private final List<LuaToken> tokens;
    private int idx;

    public LuaParser(List<LuaToken> tokens) {
        this.tokens = tokens != null ? tokens : List.of();
        this.idx = 0;
    }

    public LuaAstNode parse() throws ParseException {
        LuaAstNode chunk = new LuaAstNode(LuaAstNode.CHUNK, 1);
        while (!isEof()) {
            skipSemicolons();
            if (isEof()) {
                break;
            }
            LuaAstNode st = parseStatement();
            if (st != null) {
                chunk.addChild(st);
            }
        }
        return chunk;
    }

    private LuaAstNode parseStatement() throws ParseException {
        LuaToken t = cur();
        if (t.type() == LuaTokenType.EOF) {
            return null;
        }
        if (t.type() == LuaTokenType.KEYWORD) {
            return switch (t.lexeme()) {
                case "if" -> parseIf();
                case "while" -> parseWhile();
                case "for" -> parseFor();
                case "return" -> parseReturn();
                case "break" -> parseBreakKw();
                case "local" -> parseLocal();
                case "function" -> parseFunctionStatement();
                case "repeat" -> parseRepeat();
                default -> throw new ParseException("Unexpected keyword '" + t.lexeme() + "'", t.line(), t.column());
            };
        }
        if (t.type() == LuaTokenType.SYMBOL && ";".equals(t.lexeme())) {
            advance();
            return null;
        }
        if (t.type() == LuaTokenType.IDENTIFIER) {
            return parseAssignmentOrCallOrExprStmt();
        }
        if (t.type() == LuaTokenType.SYMBOL && "(".equals(t.lexeme())) {
            LuaAstNode e = parseExpr(0);
            LuaAstNode wrap = new LuaAstNode(LuaAstNode.EXPR_STMT, e.line());
            wrap.addChild(e);
            return wrap;
        }
        throw new ParseException("Unexpected token " + t.lexeme(), t.line(), t.column());
    }

    private LuaAstNode parseBreakKw() throws ParseException {
        LuaToken t = cur();
        advance();
        return new LuaAstNode(LuaAstNode.BREAK_STATEMENT, t.line());
    }

    private LuaAstNode parseRepeat() throws ParseException {
        int ln = cur().line();
        expectKeyword("repeat");
        LuaAstNode body = parseBlock(Set.of("until"));
        expectKeyword("until");
        LuaAstNode cond = parseExpr(0);
        LuaAstNode rep = new LuaAstNode(LuaAstNode.REPEAT_STATEMENT, ln);
        rep.addChild(body);
        rep.addChild(cond);
        return rep;
    }

    private LuaAstNode parseFunctionStatement() throws ParseException {
        return parseFunctionDef(false, false);
    }

    /**
     * Parses {@code name '=' expr} chain, {@code name '('}, or expression statement.
     */
    private LuaAstNode parseAssignmentOrCallOrExprStmt() throws ParseException {
        LuaAstNode lhs = parseSuffixedExpr();
        if (cur().type() == LuaTokenType.OPERATOR && "=".equals(cur().lexeme())) {
            advance();
            LuaAstNode rhs = parseExpr(0);
            LuaAstNode a = new LuaAstNode(LuaAstNode.ASSIGNMENT, lhs.line());
            a.setLocalAssignment(false);
            a.addChild(lhs);
            a.addChild(rhs);
            return a;
        }
        LuaAstNode ex = new LuaAstNode(LuaAstNode.EXPR_STMT, lhs.line());
        ex.addChild(lhs);
        return ex;
    }

    private LuaAstNode parseLocal() throws ParseException {
        int ln = cur().line();
        expectKeyword("local");
        if (isKeyword("function")) {
            advance();
            return parseFunctionDef(true, true);
        }
        List<LuaAstNode> names = new ArrayList<>();
        do {
            names.add(expectName());
        } while (consumeSymbol(','));
        LuaAstNode decl = new LuaAstNode(LuaAstNode.LOCAL_DECL, ln);
        for (LuaAstNode n : names) {
            decl.addChild(n);
        }
        if (consumeSymbol('=')) {
            List<LuaAstNode> rhss = parseExprList();
            if (rhss.size() != names.size()) {
                // still attach what we have; analyzer tolerates
            }
            for (LuaAstNode r : rhss) {
                decl.addChild(r);
            }
        }
        return decl;
    }

    private LuaAstNode parseFunctionDef(boolean functionKeywordAlreadyConsumed, boolean isLocal) throws ParseException {
        if (!functionKeywordAlreadyConsumed) {
            expectKeyword("function");
        }
        LuaAstNode name = expectName();
        expectSymbol('(');
        skipParamList();
        expectSymbol(')');
        LuaAstNode fn = new LuaAstNode(LuaAstNode.FUNCTION_DEF, name.line());
        fn.setText(name.text());
        fn.setLocalAssignment(isLocal);
        fn.addChild(name);
        LuaAstNode body = parseBlock(Set.of("end"));
        fn.addChild(body);
        expectKeyword("end");
        return fn;
    }

    private void skipParamList() {
        while (!isEof() && !(cur().type() == LuaTokenType.SYMBOL && ")".equals(cur().lexeme()))) {
            advance();
        }
    }

    private LuaAstNode parseIf() throws ParseException {
        int ln = cur().line();
        expectKeyword("if");
        LuaAstNode n = new LuaAstNode(LuaAstNode.IF_STATEMENT, ln);
        n.addChild(parseExpr(0));
        expectKeyword("then");
        n.addChild(parseBlock(Set.of("end", "else", "elseif")));
        while (isKeyword("elseif")) {
            advance();
            n.addChild(parseExpr(0));
            expectKeyword("then");
            n.addChild(parseBlock(Set.of("end", "else", "elseif")));
        }
        if (isKeyword("else")) {
            advance();
            n.addChild(parseBlock(Set.of("end")));
        }
        expectKeyword("end");
        return n;
    }

    private LuaAstNode parseWhile() throws ParseException {
        int ln = cur().line();
        expectKeyword("while");
        LuaAstNode cond = parseExpr(0);
        expectKeyword("do");
        LuaAstNode body = parseBlock(Set.of("end"));
        expectKeyword("end");
        LuaAstNode w = new LuaAstNode(LuaAstNode.WHILE_STATEMENT, ln);
        w.addChild(cond);
        w.addChild(body);
        return w;
    }

    private LuaAstNode parseFor() throws ParseException {
        int ln = cur().line();
        expectKeyword("for");
        LuaAstNode name = expectName();
        if (consumeSymbol('=')) {
            LuaAstNode start = parseExpr(0);
            expectSymbol(',');
            LuaAstNode end = parseExpr(0);
            LuaAstNode step = null;
            if (consumeSymbol(',')) {
                step = parseExpr(0);
            }
            expectKeyword("do");
            LuaAstNode body = parseBlock(Set.of("end"));
            expectKeyword("end");
            LuaAstNode f = new LuaAstNode(LuaAstNode.FOR_STATEMENT, ln);
            f.addChild(name);
            f.addChild(start);
            f.addChild(end);
            if (step != null) {
                f.addChild(step);
            }
            f.addChild(body);
            return f;
        }
        // generic for: for a,b in pairs(t) do ... end
        while (consumeSymbol(',')) {
            name = expectName();
        }
        expectKeyword("in");
        LuaAstNode iter = parseExpr(0);
        expectKeyword("do");
        LuaAstNode body = parseBlock(Set.of("end"));
        expectKeyword("end");
        LuaAstNode f = new LuaAstNode(LuaAstNode.FOR_STATEMENT, ln);
        f.addChild(name);
        f.addChild(iter);
        f.addChild(body);
        return f;
    }

    private LuaAstNode parseReturn() throws ParseException {
        int ln = cur().line();
        expectKeyword("return");
        if (atBlockEnd(Set.of("end", "else", "elseif", "until")) || cur().type() == LuaTokenType.EOF
                || ";".equals(cur().lexeme())) {
            LuaAstNode r = new LuaAstNode(LuaAstNode.RETURN_STATEMENT, ln);
            if (cur().type() == LuaTokenType.SYMBOL && ";".equals(cur().lexeme())) {
                advance();
            }
            return r;
        }
        List<LuaAstNode> vals = parseExprList();
        LuaAstNode r = new LuaAstNode(LuaAstNode.RETURN_STATEMENT, ln);
        for (LuaAstNode v : vals) {
            r.addChild(v);
        }
        if (cur().type() == LuaTokenType.SYMBOL && ";".equals(cur().lexeme())) {
            advance();
        }
        return r;
    }

    private List<LuaAstNode> parseExprList() throws ParseException {
        List<LuaAstNode> list = new ArrayList<>();
        list.add(parseExpr(0));
        while (consumeSymbol(',')) {
            list.add(parseExpr(0));
        }
        return list;
    }

    private LuaAstNode parseBlock(Set<String> terminators) throws ParseException {
        LuaAstNode block = new LuaAstNode(LuaAstNode.BLOCK, cur().line());
        while (!isEof()) {
            if (atBlockEnd(terminators)) {
                break;
            }
            skipSemicolons();
            if (atBlockEnd(terminators)) {
                break;
            }
            LuaAstNode st = parseStatement();
            if (st != null) {
                block.addChild(st);
            }
        }
        return block;
    }

    private boolean atBlockEnd(Set<String> terminators) {
        if (cur().type() == LuaTokenType.EOF) {
            return true;
        }
        if (cur().type() == LuaTokenType.KEYWORD && terminators.contains(cur().lexeme())) {
            return true;
        }
        return false;
    }

    private LuaAstNode parseExpr(int minPrec) throws ParseException {
        LuaAstNode left = parseUnary();
        while (true) {
            LuaToken op = cur();
            String lex = null;
            if (op.type() == LuaTokenType.OPERATOR) {
                lex = op.lexeme();
            } else if (op.type() == LuaTokenType.KEYWORD
                    && ("and".equals(op.lexeme()) || "or".equals(op.lexeme()))) {
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
            LuaAstNode right = parseExpr(nextMin);
            LuaAstNode bin = new LuaAstNode(LuaAstNode.BINARY_EXPR, left.line());
            bin.setText(lex);
            bin.addChild(left);
            bin.addChild(right);
            left = bin;
        }
        return left;
    }

    private int binaryPrecedence(String op) {
        return switch (op) {
            case "or" -> 1;
            case "and" -> 2;
            case "<", ">", "<=", ">=", "~=", "==" -> 3;
            case ".." -> 4;
            case "+", "-" -> 5;
            case "*", "/", "%", "//" -> 6;
            case "^" -> 7;
            default -> -1;
        };
    }

    private LuaAstNode parseUnary() throws ParseException {
        LuaToken t = cur();
        if (t.type() == LuaTokenType.OPERATOR && ("-".equals(t.lexeme()) || "#".equals(t.lexeme()))) {
            advance();
            LuaAstNode u = new LuaAstNode(LuaAstNode.UNARY_EXPR, t.line());
            u.setText(t.lexeme());
            u.addChild(parseUnary());
            return u;
        }
        if (t.type() == LuaTokenType.KEYWORD && "not".equals(t.lexeme())) {
            advance();
            LuaAstNode u = new LuaAstNode(LuaAstNode.UNARY_EXPR, t.line());
            u.setText("not");
            u.addChild(parseUnary());
            return u;
        }
        return parseSuffixedExpr();
    }

    private LuaAstNode parseSuffixedExpr() throws ParseException {
        LuaAstNode base = parsePrimary();
        while (true) {
            LuaToken t = cur();
            if (t.type() == LuaTokenType.SYMBOL && "(".equals(t.lexeme())) {
                base = finishCall(base);
            } else if (t.type() == LuaTokenType.SYMBOL && "[".equals(t.lexeme())) {
                advance();
                LuaAstNode idx = parseExpr(0);
                expectSymbol(']');
                LuaAstNode n = new LuaAstNode(LuaAstNode.INDEX, base.line());
                n.addChild(base);
                n.addChild(idx);
                base = n;
            } else if (t.type() == LuaTokenType.SYMBOL && ".".equals(t.lexeme())) {
                advance();
                LuaAstNode fld = expectName();
                LuaAstNode n = new LuaAstNode(LuaAstNode.FIELD, base.line());
                n.addChild(base);
                n.addChild(fld);
                base = n;
            } else {
                break;
            }
        }
        return base;
    }

    private LuaAstNode finishCall(LuaAstNode func) throws ParseException {
        expectSymbol('(');
        List<LuaAstNode> args = new ArrayList<>();
        if (!consumeSymbol(')')) {
            args.add(parseExpr(0));
            while (consumeSymbol(',')) {
                args.add(parseExpr(0));
            }
            expectSymbol(')');
        }
        LuaAstNode call = new LuaAstNode(LuaAstNode.FUNCTION_CALL, func.line());
        call.addChild(func);
        for (LuaAstNode a : args) {
            call.addChild(a);
        }
        return call;
    }

    private LuaAstNode parsePrimary() throws ParseException {
        LuaToken t = cur();
        if (t.type() == LuaTokenType.NUMBER || t.type() == LuaTokenType.STRING) {
            advance();
            LuaAstNode lit = new LuaAstNode(LuaAstNode.LITERAL, t.line());
            lit.setText(t.lexeme());
            return lit;
        }
        if (t.type() == LuaTokenType.KEYWORD) {
            if ("nil".equals(t.lexeme()) || "true".equals(t.lexeme()) || "false".equals(t.lexeme())) {
                advance();
                LuaAstNode lit = new LuaAstNode(LuaAstNode.LITERAL, t.line());
                lit.setText(t.lexeme());
                return lit;
            }
        }
        if (t.type() == LuaTokenType.IDENTIFIER) {
            advance();
            LuaAstNode n = new LuaAstNode(LuaAstNode.NAME, t.line());
            n.setText(t.lexeme());
            return n;
        }
        if (t.type() == LuaTokenType.SYMBOL && "(".equals(t.lexeme())) {
            advance();
            LuaAstNode inner = parseExpr(0);
            expectSymbol(')');
            return inner;
        }
        if (t.type() == LuaTokenType.SYMBOL && "{".equals(t.lexeme())) {
            return parseTable(t.line());
        }
        throw new ParseException("Unexpected token in expression: " + t.lexeme(), t.line(), t.column());
    }

    private LuaAstNode parseTable(int line) throws ParseException {
        expectSymbol('{');
        LuaAstNode tbl = new LuaAstNode(LuaAstNode.TABLE_CONSTRUCTOR, line);
        while (!isEof() && !(cur().type() == LuaTokenType.SYMBOL && "}".equals(cur().lexeme()))) {
            tbl.addChild(parseExpr(0));
            if (consumeSymbol(',')) {
                continue;
            }
            if (cur().type() == LuaTokenType.SYMBOL && "}".equals(cur().lexeme())) {
                break;
            }
            if (consumeSymbol(';')) {
                continue;
            }
            break;
        }
        expectSymbol('}');
        return tbl;
    }

    private LuaAstNode expectName() throws ParseException {
        LuaToken t = cur();
        if (t.type() != LuaTokenType.IDENTIFIER) {
            throw new ParseException("Expected name", t.line(), t.column());
        }
        advance();
        LuaAstNode n = new LuaAstNode(LuaAstNode.NAME, t.line());
        n.setText(t.lexeme());
        return n;
    }

    private void expectSymbol(char c) throws ParseException {
        LuaToken t = cur();
        if (t.type() != LuaTokenType.SYMBOL || !String.valueOf(c).equals(t.lexeme())) {
            throw new ParseException("Expected '" + c + "'", t.line(), t.column());
        }
        advance();
    }

    private void expectKeyword(String kw) throws ParseException {
        LuaToken t = cur();
        if (t.type() != LuaTokenType.KEYWORD || !kw.equals(t.lexeme())) {
            throw new ParseException("Expected '" + kw + "'", t.line(), t.column());
        }
        advance();
    }

    private boolean consumeSymbol(char c) {
        if (cur().type() == LuaTokenType.SYMBOL && String.valueOf(c).equals(cur().lexeme())) {
            advance();
            return true;
        }
        return false;
    }

    private boolean isKeyword(String kw) {
        return cur().type() == LuaTokenType.KEYWORD && kw.equals(cur().lexeme());
    }

    private void skipSemicolons() {
        while (cur().type() == LuaTokenType.SYMBOL && ";".equals(cur().lexeme())) {
            advance();
        }
    }

    private LuaToken cur() {
        if (idx >= tokens.size()) {
            return new LuaToken(LuaTokenType.EOF, "", lineLast(), 1);
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
        return cur().type() == LuaTokenType.EOF;
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
