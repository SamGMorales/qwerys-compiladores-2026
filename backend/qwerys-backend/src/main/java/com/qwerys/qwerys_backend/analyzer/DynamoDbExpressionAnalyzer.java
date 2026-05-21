package com.qwerys.qwerys_backend.analyzer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic checks for DynamoDB Expression Syntax across KeyCondition, Filter, Projection,
 * Update, and Condition expressions.
 */
public final class DynamoDbExpressionAnalyzer {

    private Locale ui = Locale.ENGLISH;

    private String msg(String en, String es) {
        return AnalysisMessages.t(ui, en, es);
    }

    private sealed interface Expr permits OrExpr, AndExpr, NotExpr, ParenExpr, BetweenExpr, InExpr,
            ComparisonExpr, CallExpr, PathExpr, ValueExpr {
    }

    private record OrExpr(Expr left, Expr right) implements Expr {}

    private record AndExpr(Expr left, Expr right) implements Expr {}

    private record NotExpr(Expr inner) implements Expr {}

    private record ParenExpr(Expr inner) implements Expr {}

    private record BetweenExpr(Expr path, Expr low, Expr high) implements Expr {}

    private record InExpr(Expr path, List<Expr> values) implements Expr {}

    private record ComparisonExpr(Expr left, String op, Expr right) implements Expr {}

    private record CallExpr(String name, List<Expr> args) implements Expr {}

    private record PathExpr(List<Seg> segments) implements Expr {}

    private sealed interface Seg permits NameSeg, IndexSeg {}

    private record NameSeg(String rawLexeme, boolean placeholder) implements Seg {}

    private record IndexSeg(int index) implements Seg {}

    private record ValueExpr(String placeholder) implements Expr {}

    private static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    public List<SemanticError> analyze(DynamoDbExpressionPayload payload) {
        return analyze(payload, Locale.ENGLISH);
    }

    public List<SemanticError> analyze(DynamoDbExpressionPayload payload, Locale uiLocale) {
        List<SemanticError> out = new ArrayList<>();
        this.ui = uiLocale != null ? uiLocale : Locale.ENGLISH;
        String raw = payload.expression();
        if (raw == null || raw.isBlank()) {
            out.add(new SemanticError(
                    "DDB-EXPR-EMPTY",
                    msg("DynamoDB expression is empty", "La expresión DynamoDB está vacía"),
                    msg("Provide a non-empty expression string.", "Proporcione una cadena de expresión no vacía."),
                    SemanticError.Severity.ERROR));
            return out;
        }
        try {
            switch (payload.kind()) {
                case KEY_CONDITION -> analyzeKeyCondition(payload, out);
                case PROJECTION -> analyzeProjection(payload, out);
                case UPDATE -> analyzeUpdate(payload, out);
                case CONDITION -> analyzeCondition(payload, out);
                case FILTER, UNKNOWN -> analyzeFilter(payload, out);
            }
        } catch (DynamoDbExpressionLexer.LexException le) {
            String messageText = le.detailMessage() != null && !le.detailMessage().isBlank()
                    ? le.detailMessage()
                    : msg("Invalid DynamoDB expression syntax.", "Sintaxis de expresión DynamoDB no válida.");
            String sug = msg(
                    "Fix the expression syntax near the reported position.",
                    "Corrija la sintaxis cerca de la posición indicada.");
            out.add(new SemanticError(
                    "DDB-EXPR-LEX",
                    messageText,
                    sug,
                    SemanticError.Severity.ERROR,
                    le.line(),
                    le.column()));
        } catch (ParseException pe) {
            out.add(new SemanticError(
                    "DDB-EXPR-PARSE",
                    pe.getMessage(),
                    msg("Check parentheses, operators, and function call arguments.",
                            "Revise paréntesis, operadores y argumentos de funciones."),
                    SemanticError.Severity.ERROR));
        } catch (IllegalArgumentException iae) {
            out.add(new SemanticError(
                    "DDB-EXPR-INPUT",
                    iae.getMessage(),
                    msg("Provide valid JSON with kind and expression when using structured input.",
                            "Envíe JSON válido con kind y expression al usar entrada estructurada."),
                    SemanticError.Severity.ERROR));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // KEY_CONDITION
    // -------------------------------------------------------------------------

    private void analyzeKeyCondition(DynamoDbExpressionPayload p, List<SemanticError> out) {
        Expr ast = parseExpr(p.expression());
        if (containsOr(ast)) {
            out.add(new SemanticError(
                    "DDB-KCE-NO-OR",
                    msg("KeyConditionExpression does not support OR operator",
                            "KeyConditionExpression no admite el operador OR"),
                    msg("Use AND only, or issue separate query operations.",
                            "Use solo AND u o ejecute consultas separadas."),
                    SemanticError.Severity.ERROR));
        }
        String pkAttr = p.partitionKeyAttributeName();
        if (pkAttr == null || pkAttr.isBlank()) {
            out.add(new SemanticError(
                    "DDB-KCE-PK-META",
                    msg("Cannot validate the partition key without metadata",
                            "No se puede validar la clave de partición sin metadatos"),
                    msg("Include partitionKeyAttributeName and expressionAttributeNames in your JSON payload.",
                            "Incluya partitionKeyAttributeName y expressionAttributeNames en el JSON."),
                    SemanticError.Severity.ERROR));
            return;
        }
        Set<String> pkHolders = placeholdersForAttribute(p.expressionAttributeNames(), pkAttr);
        List<Cond> conjuncts = flattenAnd(ast);
        List<Cond> pkConds = new ArrayList<>();
        for (Cond c : conjuncts) {
            if (involvesPk(c, pkAttr, pkHolders, p.expressionAttributeNames())) {
                pkConds.add(c);
            }
        }
        if (pkConds.isEmpty()) {
            out.add(new SemanticError(
                    "DDB-KCE-PK-MISSING",
                    msg("KeyConditionExpression must include the partition key",
                            "KeyConditionExpression debe incluir la clave de partición"),
                    msg("Add an equality condition on the partition key attribute or its #placeholder.",
                            "Añada una condición de igualdad sobre el atributo de clave de partición o su #placeholder."),
                    SemanticError.Severity.ERROR));
        } else if (pkConds.size() > 1) {
            out.add(new SemanticError(
                    "DDB-KCE-PK-MULTI",
                    msg("Only one condition allowed on partition key",
                            "Solo se permite una condición sobre la clave de partición"),
                    msg("Combine logic into a single partition key condition.",
                            "Combine la lógica en una única condición sobre la clave de partición."),
                    SemanticError.Severity.ERROR));
        } else {
            validatePartitionKeyShape(pkConds.get(0), pkAttr, pkHolders, p.expressionAttributeNames(), out);
        }

        String skAttr = p.sortKeyAttributeName();
        if (skAttr != null && !skAttr.isBlank()) {
            Set<String> skHolders = placeholdersForAttribute(p.expressionAttributeNames(), skAttr);
            for (Cond c : conjuncts) {
                if (involvesPk(c, skAttr, skHolders, p.expressionAttributeNames())) {
                    validateSortKeyShape(c, skAttr, skHolders, p.expressionAttributeNames(), out);
                }
            }
        }
    }

    private void validatePartitionKeyShape(
            Cond c,
            String pkAttr,
            Set<String> pkHolders,
            Map<String, String> names,
            List<SemanticError> out) {
        switch (c.kind()) {
            case COMPARISON -> {
                if (!"=".equals(c.op())) {
                    out.add(new SemanticError(
                            "DDB-KCE-PK-OP",
                            msg("Partition key in KeyConditionExpression only supports = operator",
                                    "En KeyConditionExpression la clave de partición solo admite el operador ="),
                            msg("Use = between the partition key path and a single value placeholder.",
                                    "Use = entre la ruta de la clave de partición y un único placeholder de valor."),
                            SemanticError.Severity.ERROR));
                    return;
                }
                PathLike leftPath = pathLike(c.left());
                PathLike rightPath = pathLike(c.right());
                boolean touchesPk = (leftPath != null && pathTargetsAttribute(leftPath, pkAttr, pkHolders, names))
                        || (rightPath != null && pathTargetsAttribute(rightPath, pkAttr, pkHolders, names));
                if (!touchesPk) {
                    out.add(new SemanticError(
                            "DDB-KCE-PK-OP",
                            msg("Partition key in KeyConditionExpression only supports = operator",
                                    "En KeyConditionExpression la clave de partición solo admite el operador ="),
                            msg("Ensure one side of = is the partition key path and the other is a value placeholder.",
                                    "Asegúrese de que un lado del = sea la ruta de la clave de partición y el otro un placeholder de valor."),
                            SemanticError.Severity.ERROR));
                }
            }
            case BETWEEN, IN -> out.add(new SemanticError(
                    "DDB-KCE-PK-OP",
                    msg("Partition key in KeyConditionExpression only supports = operator",
                            "En KeyConditionExpression la clave de partición solo admite el operador ="),
                    msg("BETWEEN / IN are not valid for the partition key.",
                            "BETWEEN / IN no son válidos para la clave de partición."),
                    SemanticError.Severity.ERROR));
            case CALL -> {
                String fn = c.fn();
                if ("begins_with".equalsIgnoreCase(fn)) {
                    List<Expr> args = c.args();
                    PathLike pl = args.isEmpty() ? null : pathLike(args.get(0));
                    if (pl != null && pathTargetsAttribute(pl, pkAttr, pkHolders, names)) {
                        out.add(new SemanticError(
                                "DDB-KCE-PK-OP",
                                msg("Partition key in KeyConditionExpression only supports = operator",
                                        "En KeyConditionExpression la clave de partición solo admite el operador ="),
                                msg("begins_with is only valid on the sort key.",
                                        "begins_with solo es válido en la clave de ordenación."),
                                SemanticError.Severity.ERROR));
                    }
                } else {
                    PathLike pl = pathLike(c.fnExpr());
                    if (pl != null && pathTargetsAttribute(pl, pkAttr, pkHolders, names)) {
                        out.add(new SemanticError(
                                "DDB-KCE-PK-OP",
                                msg("Partition key in KeyConditionExpression only supports = operator",
                                        "En KeyConditionExpression la clave de partición solo admite el operador ="),
                                msg("Use a single equality for the partition key.",
                                        "Use una sola igualdad para la clave de partición."),
                                SemanticError.Severity.ERROR));
                    }
                }
            }
        }
    }

    private void validateSortKeyShape(
            Cond c,
            String skAttr,
            Set<String> skHolders,
            Map<String, String> names,
            List<SemanticError> out) {
        switch (c.kind()) {
            case COMPARISON -> {
                String op = c.op();
                PathLike leftPath = pathLike(c.left());
                PathLike rightPath = pathLike(c.right());
                boolean touchesSk = (leftPath != null && pathTargetsAttribute(leftPath, skAttr, skHolders, names))
                        || (rightPath != null && pathTargetsAttribute(rightPath, skAttr, skHolders, names));
                if (!touchesSk) {
                    return;
                }
                if ("=".equals(op)
                        || "<".equals(op)
                        || ">".equals(op)
                        || "<=".equals(op)
                        || ">=".equals(op)) {
                    return;
                }
                if ("<>".equals(op) || "!=".equals(op)) {
                    out.add(new SemanticError(
                            "DDB-KCE-SK-OP",
                            msg("Unsupported operator on sort key in KeyConditionExpression",
                                    "Operador no admitido en la clave de ordenación en KeyConditionExpression"),
                            msg("Use =, <, >, <=, >=, BETWEEN, or begins_with on the sort key.",
                                    "Use =, <, >, <=, >=, BETWEEN o begins_with en la clave de ordenación."),
                            SemanticError.Severity.ERROR));
                    return;
                }
                out.add(new SemanticError(
                        "DDB-KCE-SK-OP",
                        msg("Unsupported operator on sort key in KeyConditionExpression",
                                "Operador no admitido en la clave de ordenación en KeyConditionExpression"),
                        msg("Use =, <, >, <=, >=, BETWEEN, or begins_with on the sort key.",
                                "Use =, <, >, <=, >=, BETWEEN o begins_with en la clave de ordenación."),
                        SemanticError.Severity.ERROR));
            }
            case BETWEEN -> {
                PathLike pl = pathLike(c.betweenPath());
                if (pl != null && pathTargetsAttribute(pl, skAttr, skHolders, names)) {
                    return;
                }
            }
            case IN -> out.add(new SemanticError(
                    "DDB-KCE-SK-OP",
                    msg("Unsupported operator on sort key in KeyConditionExpression",
                            "Operador no admitido en la clave de ordenación en KeyConditionExpression"),
                    msg("IN is not supported for sort keys in KeyConditionExpression.",
                            "IN no está admitido para claves de ordenación en KeyConditionExpression."),
                    SemanticError.Severity.ERROR));
            case CALL -> {
                String fn = c.fn();
                if ("begins_with".equalsIgnoreCase(fn)) {
                    List<Expr> args = c.args();
                    PathLike pl = args.isEmpty() ? null : pathLike(args.get(0));
                    if (pl == null || !pathTargetsAttribute(pl, skAttr, skHolders, names)) {
                        return;
                    }
                    return;
                }
                List<Expr> args = c.args();
                PathLike touched = pathLike(c.fnExpr());
                if (touched != null && pathTargetsAttribute(touched, skAttr, skHolders, names)) {
                    out.add(new SemanticError(
                            "DDB-KCE-SK-FUNC",
                            msg("Only begins_with() is allowed on sort key in KeyConditionExpression",
                                    "Solo begins_with() está permitido en la clave de ordenación en KeyConditionExpression"),
                            msg("Remove function calls other than begins_with on the sort key path.",
                                    "Elimine llamadas distintas de begins_with en la ruta de la clave de ordenación."),
                            SemanticError.Severity.ERROR));
                    return;
                }
                if (!args.isEmpty()) {
                    PathLike pl = pathLike(args.get(0));
                    if (pl != null && pathTargetsAttribute(pl, skAttr, skHolders, names)) {
                        out.add(new SemanticError(
                                "DDB-KCE-SK-FUNC",
                                msg("Only begins_with() is allowed on sort key in KeyConditionExpression",
                                        "Solo begins_with() está permitido en la clave de ordenación en KeyConditionExpression"),
                                msg("Remove function calls other than begins_with on the sort key path.",
                                        "Elimine llamadas distintas de begins_with en la ruta de la clave de ordenación."),
                                SemanticError.Severity.ERROR));
                    }
                }
            }
        }
    }

    private enum CondKind {
        COMPARISON,
        BETWEEN,
        IN,
        CALL
    }

    /**
     * Normalized primitive condition extracted from AND-flattened AST.
     */
    private static final class Cond {
        private final CondKind kind;
        private final Expr left;
        private final String op;
        private final Expr right;
        private final Expr betweenPath;
        private final Expr betweenLow;
        private final Expr betweenHigh;
        private final Expr path;
        private final List<Expr> inVals;
        private final String fn;
        private final List<Expr> args;
        private final Expr fnExpr;

        private Cond(
                CondKind kind,
                Expr left,
                String op,
                Expr right,
                Expr betweenPath,
                Expr betweenLow,
                Expr betweenHigh,
                Expr path,
                List<Expr> inVals,
                String fn,
                List<Expr> args,
                Expr fnExpr) {
            this.kind = kind;
            this.left = left;
            this.op = op;
            this.right = right;
            this.betweenPath = betweenPath;
            this.betweenLow = betweenLow;
            this.betweenHigh = betweenHigh;
            this.path = path;
            this.inVals = inVals;
            this.fn = fn;
            this.args = args;
            this.fnExpr = fnExpr;
        }

        static Cond comparison(Expr l, String op, Expr r) {
            return new Cond(CondKind.COMPARISON, l, op, r, null, null, null, null, null, null, null, null);
        }

        static Cond between(Expr path, Expr low, Expr high) {
            return new Cond(CondKind.BETWEEN, null, null, null, path, low, high, null, null, null, null, null);
        }

        static Cond in(Expr path, List<Expr> vals) {
            return new Cond(CondKind.IN, null, null, null, null, null, null, path, vals, null, null, null);
        }

        static Cond call(Expr fnExpr, String fn, List<Expr> args) {
            return new Cond(CondKind.CALL, null, null, null, null, null, null, null, null, fn, args, fnExpr);
        }

        CondKind kind() {
            return kind;
        }

        Expr left() {
            return left;
        }

        Expr right() {
            return right;
        }

        String op() {
            return op;
        }

        Expr betweenPath() {
            return betweenPath;
        }

        Expr fnExpr() {
            return fnExpr;
        }

        String fn() {
            return fn;
        }

        List<Expr> args() {
            return args == null ? List.of() : args;
        }
    }

    private static List<Cond> flattenAnd(Expr root) {
        List<Cond> out = new ArrayList<>();
        Deque<Expr> dq = new ArrayDeque<>();
        dq.add(root);
        while (!dq.isEmpty()) {
            Expr n = dq.removeFirst();
            Expr u = unwrap(n);
            if (u instanceof AndExpr a) {
                dq.addFirst(a.right());
                dq.addFirst(a.left());
            } else {
                out.add(condFromExpr(u));
            }
        }
        return out;
    }

    private static Cond condFromExpr(Expr n) {
        Expr u = unwrap(n);
        if (u instanceof ComparisonExpr c) {
            return Cond.comparison(c.left(), c.op(), c.right());
        }
        if (u instanceof BetweenExpr b) {
            return Cond.between(b.path(), b.low(), b.high());
        }
        if (u instanceof InExpr i) {
            return Cond.in(i.path(), i.values());
        }
        if (u instanceof CallExpr c) {
            return Cond.call(c, c.name(), c.args());
        }
        return Cond.call(u, "", List.of());
    }

    private static Expr unwrap(Expr n) {
        Expr cur = n;
        while (cur instanceof ParenExpr p) {
            cur = p.inner();
        }
        while (cur instanceof NotExpr nx && nx.inner() instanceof ParenExpr p2) {
            cur = p2.inner();
        }
        return cur;
    }

    private static boolean containsOr(Expr n) {
        if (n == null) {
            return false;
        }
        if (n instanceof OrExpr o) {
            return true;
        }
        if (n instanceof AndExpr a) {
            return containsOr(a.left()) || containsOr(a.right());
        }
        if (n instanceof NotExpr nx) {
            return containsOr(nx.inner());
        }
        if (n instanceof ParenExpr p) {
            return containsOr(p.inner());
        }
        return false;
    }

    private record PathLike(List<Seg> segments) {}

    private static PathLike pathLike(Expr e) {
        Expr u = unwrap(e);
        if (u instanceof PathExpr p) {
            return new PathLike(p.segments());
        }
        if (u instanceof ValueExpr || u instanceof CallExpr || u instanceof OrExpr || u instanceof AndExpr) {
            return null;
        }
        return null;
    }

    private static boolean involvesPk(
            Cond c,
            String attr,
            Set<String> placeholders,
            Map<String, String> names) {
        return switch (c.kind()) {
            case COMPARISON -> {
                PathLike l = pathLike(c.left());
                PathLike r = pathLike(c.right());
                yield (l != null && pathTargetsAttribute(l, attr, placeholders, names))
                        || (r != null && pathTargetsAttribute(r, attr, placeholders, names));
            }
            case BETWEEN -> {
                PathLike pl = pathLike(c.betweenPath);
                yield pl != null && pathTargetsAttribute(pl, attr, placeholders, names);
            }
            case IN -> {
                PathLike pl = pathLike(c.path);
                yield pl != null && pathTargetsAttribute(pl, attr, placeholders, names);
            }
            case CALL -> {
                for (Expr a : c.args()) {
                    PathLike pl = pathLike(a);
                    if (pl != null && pathTargetsAttribute(pl, attr, placeholders, names)) {
                        yield true;
                    }
                }
                PathLike f = pathLike(c.fnExpr());
                yield f != null && pathTargetsAttribute(f, attr, placeholders, names);
            }
        };
    }

    private static boolean pathTargetsAttribute(
            PathLike pl,
            String attr,
            Set<String> placeholders,
            Map<String, String> names) {
        if (pl.segments().isEmpty()) {
            return false;
        }
        Seg first = pl.segments().get(0);
        if (!(first instanceof NameSeg ns)) {
            return false;
        }
        if (ns.placeholder()) {
            return placeholders.contains(ns.rawLexeme());
        }
        return attr.equalsIgnoreCase(ns.rawLexeme());
    }

    private static Set<String> placeholdersForAttribute(Map<String, String> names, String attr) {
        Set<String> s = new HashSet<>();
        if (names == null || attr == null) {
            return s;
        }
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (attr.equalsIgnoreCase(e.getValue())) {
                s.add(e.getKey());
            }
        }
        return s;
    }

    // -------------------------------------------------------------------------
    // FILTER
    // -------------------------------------------------------------------------

    private void analyzeFilter(DynamoDbExpressionPayload p, List<SemanticError> out) {
        Expr ast = parseExpr(p.expression());
        checkFunctionArity(ast, out, false);
        checkSizeNeedsComparison(ast, out);
        checkInListSize(ast, out);
        checkAttrAttrComparison(ast, out);
        maybeWarnFilterInsteadOfKeyCondition(p, ast, out);
    }

    private void checkFunctionArity(Expr root, List<SemanticError> out, boolean projectionMode) {
        if (root == null) {
            return;
        }
        if (root instanceof OrExpr o) {
            checkFunctionArity(o.left(), out, projectionMode);
            checkFunctionArity(o.right(), out, projectionMode);
        } else if (root instanceof AndExpr a) {
            checkFunctionArity(a.left(), out, projectionMode);
            checkFunctionArity(a.right(), out, projectionMode);
        } else if (root instanceof NotExpr n) {
            checkFunctionArity(n.inner(), out, projectionMode);
        } else if (root instanceof ParenExpr p) {
            checkFunctionArity(p.inner(), out, projectionMode);
        } else if (root instanceof BetweenExpr b) {
            checkFunctionArity(b.path(), out, projectionMode);
            checkFunctionArity(b.low(), out, projectionMode);
            checkFunctionArity(b.high(), out, projectionMode);
        } else if (root instanceof InExpr i) {
            checkFunctionArity(i.path(), out, projectionMode);
            for (Expr v : i.values()) {
                checkFunctionArity(v, out, projectionMode);
            }
        } else if (root instanceof ComparisonExpr c) {
            checkFunctionArity(c.left(), out, projectionMode);
            checkFunctionArity(c.right(), out, projectionMode);
        } else if (root instanceof CallExpr call) {
            String name = call.name() != null ? call.name().toLowerCase(Locale.ROOT) : "";
            int argc = call.args().size();
            switch (name) {
                case "attribute_exists", "attribute_not_exists", "attribute_type" -> {
                    if (argc != 1) {
                        boolean exists = "attribute_exists".equals(name);
                        out.add(new SemanticError(
                                exists ? "DDB-FE-ATTR-EXISTS-ARGS" : "DDB-FE-ATTR-NOT-EXISTS-ARGS",
                                exists
                                        ? msg("attribute_exists requires exactly one argument: the attribute path",
                                                "attribute_exists requiere exactamente un argumento: la ruta del atributo")
                                        : msg("attribute_not_exists requires exactly one argument",
                                                "attribute_not_exists requiere exactamente un argumento"),
                                exists
                                        ? msg("Use attribute_exists(#name.path).", "Use attribute_exists(#nombre.ruta).")
                                        : msg("Use attribute_not_exists(#name.path).",
                                                "Use attribute_not_exists(#nombre.ruta)."),
                                SemanticError.Severity.ERROR));
                    }
                }
                case "contains" -> {
                    if (argc < 2) {
                        out.add(new SemanticError(
                                "DDB-FE-CONTAINS-ARGS",
                                msg("contains requires two arguments: contains(path, operand)",
                                        "contains requiere dos argumentos: contains(ruta, operando)"),
                                msg("Provide the attribute path and the substring/value placeholder.",
                                        "Indique la ruta del atributo y el placeholder de subcadena/valor."),
                                SemanticError.Severity.ERROR));
                    }
                }
                case "begins_with" -> {
                    if (argc < 2) {
                        out.add(new SemanticError(
                                "DDB-FE-BEGINS-ARGS",
                                msg("begins_with requires two arguments: begins_with(path, substr)",
                                        "begins_with requiere dos argumentos: begins_with(ruta, prefijo)"),
                                msg("Provide the attribute path and the prefix placeholder.",
                                        "Indique la ruta del atributo y el placeholder del prefijo."),
                                SemanticError.Severity.ERROR));
                    }
                }
                default -> {
                    for (Expr arg : call.args()) {
                        checkFunctionArity(arg, out, projectionMode);
                    }
                }
            }
            for (Expr arg : call.args()) {
                checkFunctionArity(arg, out, projectionMode);
            }
        }
    }

    private void checkSizeNeedsComparison(Expr root, List<SemanticError> out) {
        List<CallExpr> sizes = new ArrayList<>();
        collectCalls(root, "size", sizes);
        for (CallExpr c : sizes) {
            if (!isComparedAnywhere(root, c)) {
                out.add(new SemanticError(
                        "DDB-FE-SIZE-COMP",
                        msg("size() must be used with a comparison operator: size(attr) > :val",
                                "size() debe usarse con un operador de comparación: size(attr) > :val"),
                        msg("Wrap size(...) on the left or right side of =, <>, <, >, <=, or >=.",
                                "Coloque size(...) a la izquierda o derecha de =, <>, <, >, <= o >=."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private static void collectCalls(Expr n, String fn, List<CallExpr> acc) {
        if (n == null) {
            return;
        }
        if (n instanceof CallExpr c && fn.equalsIgnoreCase(c.name())) {
            acc.add(c);
        }
        if (n instanceof OrExpr o) {
            collectCalls(o.left(), fn, acc);
            collectCalls(o.right(), fn, acc);
        } else if (n instanceof AndExpr a) {
            collectCalls(a.left(), fn, acc);
            collectCalls(a.right(), fn, acc);
        } else if (n instanceof NotExpr nx) {
            collectCalls(nx.inner(), fn, acc);
        } else if (n instanceof ParenExpr p) {
            collectCalls(p.inner(), fn, acc);
        } else if (n instanceof BetweenExpr b) {
            collectCalls(b.path(), fn, acc);
            collectCalls(b.low(), fn, acc);
            collectCalls(b.high(), fn, acc);
        } else if (n instanceof InExpr i) {
            collectCalls(i.path(), fn, acc);
            for (Expr v : i.values()) {
                collectCalls(v, fn, acc);
            }
        } else if (n instanceof ComparisonExpr c) {
            collectCalls(c.left(), fn, acc);
            collectCalls(c.right(), fn, acc);
        } else if (n instanceof CallExpr c) {
            for (Expr arg : c.args()) {
                collectCalls(arg, fn, acc);
            }
        }
    }

    private static boolean isComparedAnywhere(Expr root, CallExpr target) {
        List<ComparisonExpr> comps = new ArrayList<>();
        collectComparisons(root, comps);
        for (ComparisonExpr c : comps) {
            if (subtreeRefEq(c.left(), target) || subtreeRefEq(c.right(), target)) {
                return true;
            }
        }
        return false;
    }

    private static void collectComparisons(Expr n, List<ComparisonExpr> acc) {
        if (n == null) {
            return;
        }
        if (n instanceof ComparisonExpr c) {
            acc.add(c);
        }
        if (n instanceof OrExpr o) {
            collectComparisons(o.left(), acc);
            collectComparisons(o.right(), acc);
        } else if (n instanceof AndExpr a) {
            collectComparisons(a.left(), acc);
            collectComparisons(a.right(), acc);
        } else if (n instanceof NotExpr nx) {
            collectComparisons(nx.inner(), acc);
        } else if (n instanceof ParenExpr p) {
            collectComparisons(p.inner(), acc);
        } else if (n instanceof BetweenExpr b) {
            collectComparisons(b.path(), acc);
            collectComparisons(b.low(), acc);
            collectComparisons(b.high(), acc);
        } else if (n instanceof InExpr i) {
            collectComparisons(i.path(), acc);
            for (Expr v : i.values()) {
                collectComparisons(v, acc);
            }
        } else if (n instanceof CallExpr c) {
            for (Expr arg : c.args()) {
                collectComparisons(arg, acc);
            }
        }
    }

    private static boolean subtreeRefEq(Expr n, Expr target) {
        if (n == null) {
            return false;
        }
        if (n == target) {
            return true;
        }
        if (n instanceof OrExpr o) {
            return subtreeRefEq(o.left(), target) || subtreeRefEq(o.right(), target);
        }
        if (n instanceof AndExpr a) {
            return subtreeRefEq(a.left(), target) || subtreeRefEq(a.right(), target);
        }
        if (n instanceof NotExpr nx) {
            return subtreeRefEq(nx.inner(), target);
        }
        if (n instanceof ParenExpr p) {
            return subtreeRefEq(p.inner(), target);
        }
        if (n instanceof BetweenExpr b) {
            return subtreeRefEq(b.path(), target)
                    || subtreeRefEq(b.low(), target)
                    || subtreeRefEq(b.high(), target);
        }
        if (n instanceof InExpr i) {
            if (subtreeRefEq(i.path(), target)) {
                return true;
            }
            for (Expr v : i.values()) {
                if (subtreeRefEq(v, target)) {
                    return true;
                }
            }
            return false;
        }
        if (n instanceof ComparisonExpr c) {
            return subtreeRefEq(c.left(), target) || subtreeRefEq(c.right(), target);
        }
        if (n instanceof CallExpr c) {
            for (Expr arg : c.args()) {
                if (subtreeRefEq(arg, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkInListSize(Expr root, List<SemanticError> out) {
        if (root == null) {
            return;
        }
        if (root instanceof InExpr i && i.values().size() > 100) {
            out.add(new SemanticError(
                    "DDB-FE-IN-LARGE",
                    msg("IN operator with many values may impact performance; consider batching",
                            "Un IN con muchos valores puede afectar al rendimiento; considere procesarlo por lotes"),
                    msg("Split the request into batches or redesign the access pattern.",
                            "Divida la petición en lotes o rediseñe el patrón de acceso."),
                    SemanticError.Severity.WARNING));
        }
        if (root instanceof OrExpr o) {
            checkInListSize(o.left(), out);
            checkInListSize(o.right(), out);
        } else if (root instanceof AndExpr a) {
            checkInListSize(a.left(), out);
            checkInListSize(a.right(), out);
        } else if (root instanceof NotExpr n) {
            checkInListSize(n.inner(), out);
        } else if (root instanceof ParenExpr p) {
            checkInListSize(p.inner(), out);
        } else if (root instanceof BetweenExpr b) {
            checkInListSize(b.path(), out);
            checkInListSize(b.low(), out);
            checkInListSize(b.high(), out);
        } else if (root instanceof ComparisonExpr c) {
            checkInListSize(c.left(), out);
            checkInListSize(c.right(), out);
        } else if (root instanceof CallExpr c) {
            for (Expr arg : c.args()) {
                checkInListSize(arg, out);
            }
        }
    }

    private void checkAttrAttrComparison(Expr root, List<SemanticError> out) {
        if (root == null) {
            return;
        }
        if (root instanceof ComparisonExpr cmp) {
            PathCall pcL = classifyComparisonOperand(cmp.left());
            PathCall pcR = classifyComparisonOperand(cmp.right());
            if (pcL == PathCall.PATH && pcR == PathCall.PATH) {
                out.add(new SemanticError(
                        "DDB-FE-TYPE-MIX",
                        msg("Comparing attributes of different types may yield unexpected results in DynamoDB",
                                "Comparar atributos de distinto tipo puede dar resultados inesperados en DynamoDB"),
                        msg("Compare each side to a typed value placeholder when possible.",
                                "Compare cada lado con un placeholder de valor tipado cuando sea posible."),
                        SemanticError.Severity.WARNING));
            }
        }
        if (root instanceof OrExpr o) {
            checkAttrAttrComparison(o.left(), out);
            checkAttrAttrComparison(o.right(), out);
        } else if (root instanceof AndExpr a) {
            checkAttrAttrComparison(a.left(), out);
            checkAttrAttrComparison(a.right(), out);
        } else if (root instanceof NotExpr n) {
            checkAttrAttrComparison(n.inner(), out);
        } else if (root instanceof ParenExpr p) {
            checkAttrAttrComparison(p.inner(), out);
        } else if (root instanceof BetweenExpr b) {
            checkAttrAttrComparison(b.path(), out);
            checkAttrAttrComparison(b.low(), out);
            checkAttrAttrComparison(b.high(), out);
        } else if (root instanceof InExpr i) {
            checkAttrAttrComparison(i.path(), out);
            for (Expr v : i.values()) {
                checkAttrAttrComparison(v, out);
            }
        } else if (root instanceof CallExpr c) {
            for (Expr arg : c.args()) {
                checkAttrAttrComparison(arg, out);
            }
        }
    }

    private enum PathCall {
        PATH,
        NON_PATH
    }

    private static PathCall classifyComparisonOperand(Expr e) {
        Expr u = unwrap(e);
        if (u instanceof PathExpr) {
            return PathCall.PATH;
        }
        if (u instanceof CallExpr) {
            return PathCall.NON_PATH;
        }
        return PathCall.NON_PATH;
    }

    private void maybeWarnFilterInsteadOfKeyCondition(
            DynamoDbExpressionPayload p,
            Expr ast,
            List<SemanticError> out) {
        String pkAttr = p.partitionKeyAttributeName();
        if (pkAttr == null || pkAttr.isBlank()) {
            return;
        }
        Set<String> holders = placeholdersForAttribute(p.expressionAttributeNames(), pkAttr);
        List<Cond> conj = flattenAnd(ast);
        for (Cond c : conj) {
            if (c.kind() == CondKind.COMPARISON && "=".equals(c.op())) {
                PathLike pl = pathLike(c.left());
                PathLike pr = pathLike(c.right());
                boolean pkEq = (pl != null && pathTargetsAttribute(pl, pkAttr, holders, p.expressionAttributeNames()))
                        || (pr != null && pathTargetsAttribute(pr, pkAttr, holders, p.expressionAttributeNames()));
                if (pkEq) {
                    out.add(new SemanticError(
                            "DDB-FE-KEY-FILTER",
                            msg("FilterExpression runs AFTER data is read; move conditions to KeyConditionExpression when possible to reduce RCU cost",
                                    "FilterExpression se aplica DESPUÉS de leer datos; mueva condiciones a KeyConditionExpression cuando pueda para reducir RCU"),
                            msg("Promote partition-key equality to KeyConditionExpression on Query operations.",
                                    "Promueva la igualdad de clave de partición a KeyConditionExpression en operaciones Query."),
                            SemanticError.Severity.WARNING));
                    return;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // PROJECTION
    // -------------------------------------------------------------------------

    private void analyzeProjection(DynamoDbExpressionPayload p, List<SemanticError> out) {
        String expr = p.expression();
        List<String> paths = splitTopLevelComma(expr);
        Set<String> seen = new LinkedHashSet<>();
        for (String path : paths) {
            String pe = path.strip();
            if (pe.isEmpty()) {
                continue;
            }
            List<DynamoDbExpressionToken> toks = new DynamoDbExpressionLexer(pe).tokenize();
            // Drop EOF for scan
            for (int i = 0; i + 1 < toks.size(); i++) {
                DynamoDbExpressionToken t = toks.get(i);
                if (t.type() == DynamoDbExpressionTokenType.ATTR_NAME) {
                    if (!p.expressionAttributeNames().containsKey(t.lexeme())) {
                        out.add(new SemanticError(
                                "DDB-PE-NAME-MAP",
                                msg("ExpressionAttributeNames must map #name to actual attribute name",
                                        "ExpressionAttributeNames debe mapear #nombre al nombre real del atributo"),
                                msg("Add an entry in expressionAttributeNames for " + t.lexeme() + ".",
                                        "Añada una entrada en expressionAttributeNames para " + t.lexeme() + "."),
                                SemanticError.Severity.ERROR));
                    }
                }
                if (t.type() == DynamoDbExpressionTokenType.ATTR_VALUE) {
                    if (!p.expressionAttributeValues().containsKey(t.lexeme())) {
                        out.add(new SemanticError(
                                "DDB-PE-VAL-MAP",
                                msg("ExpressionAttributeValues must map :value to actual value",
                                        "ExpressionAttributeValues debe mapear :valor al valor real"),
                                msg("ProjectionExpression should not use :values; remove them or map in expressionAttributeValues.",
                                        "ProjectionExpression no debería usar :valores; elimínelos o mapéelos en expressionAttributeValues."),
                                SemanticError.Severity.ERROR));
                    }
                }
            }
            String norm = normalizeProjectionPath(pe, p.expressionAttributeNames());
            if (!seen.add(norm)) {
                out.add(new SemanticError(
                        "DDB-PE-DUP",
                        msg("Duplicate attribute in ProjectionExpression",
                                "Atributo duplicado en ProjectionExpression"),
                        msg("Remove repeated paths to avoid redundant work.",
                                "Elimine rutas repetidas para evitar trabajo redundante."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private static String normalizeProjectionPath(String pathExpr, Map<String, String> names) {
        try {
            Expr ast = parsePathExprOnly(pathExpr);
            if (ast instanceof PathExpr p) {
                StringBuilder sb = new StringBuilder();
                for (Seg s : p.segments()) {
                    if (s instanceof NameSeg ns) {
                        if (ns.placeholder()) {
                            String attr = names.get(ns.rawLexeme());
                            sb.append(attr != null ? attr : ns.rawLexeme());
                        } else {
                            sb.append(ns.rawLexeme());
                        }
                    } else if (s instanceof IndexSeg ix) {
                        sb.append('[').append(ix.index()).append(']');
                    }
                    sb.append('/');
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // fall back to raw
        }
        return pathExpr.replace(" ", "");
    }

    private static List<String> splitTopLevelComma(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    // -------------------------------------------------------------------------
    // UPDATE
    // -------------------------------------------------------------------------

    private void analyzeUpdate(DynamoDbExpressionPayload p, List<SemanticError> out) {
        String expr = p.expression();
        List<ClauseSlice> clauses = sliceUpdateClauses(expr);
        Set<String> setPaths = new HashSet<>();
        Set<String> removePaths = new HashSet<>();
        Set<String> addPaths = new HashSet<>();
        Set<String> deletePaths = new HashSet<>();
        for (ClauseSlice sl : clauses) {
            switch (sl.kind()) {
                case SET -> collectSetLhs(sl.body(), setPaths, p.expressionAttributeNames());
                case REMOVE -> collectRemovePaths(sl.body(), removePaths, p.expressionAttributeNames());
                case ADD -> collectSimplePaths(sl.body(), addPaths, p.expressionAttributeNames());
                case DELETE -> collectSimplePaths(sl.body(), deletePaths, p.expressionAttributeNames());
            }
        }
        for (String path : setPaths) {
            if (removePaths.contains(path)) {
                out.add(new SemanticError(
                        "DDB-UE-SET-REMOVE",
                        msg("Cannot SET and REMOVE the same attribute in one UpdateExpression",
                                "No puede SET y REMOVE el mismo atributo en una UpdateExpression"),
                        msg("Keep either SET or REMOVE for " + path + ".",
                                "Mantenga solo SET o solo REMOVE para " + path + "."),
                        SemanticError.Severity.ERROR));
            }
        }
        Map<String, String> types = p.attributeTypes();
        for (String ap : addPaths) {
            String t = types.get(ap);
            if (t != null && ("S".equalsIgnoreCase(t) || "M".equalsIgnoreCase(t))) {
                out.add(new SemanticError(
                        "DDB-UE-ADD-TYPE",
                        msg("ADD action only works on Number and Set types",
                                "ADD solo funciona con tipos Number y Set"),
                        msg("Use SET for strings/maps or change the attribute type.",
                                "Use SET para strings/mapas o cambie el tipo del atributo."),
                        SemanticError.Severity.ERROR));
            }
        }
        for (String dp : deletePaths) {
            String t = types.get(dp);
            if (t != null && !("SS".equalsIgnoreCase(t) || "NS".equalsIgnoreCase(t)
                    || "BS".equalsIgnoreCase(t))) {
                out.add(new SemanticError(
                        "DDB-UE-DEL-TYPE",
                        msg("DELETE action only works on Set types",
                                "DELETE solo funciona con tipos Set"),
                        msg("DELETE removes elements from sets (SS/NS/BS).",
                                "DELETE elimina elementos de conjuntos (SS/NS/BS)."),
                        SemanticError.Severity.ERROR));
            }
        }
        checkUpdateIfNotExists(clauses, out);
        warnListAppendNonList(p, clauses, out);
    }

    private enum SliceKind {
        SET,
        REMOVE,
        ADD,
        DELETE
    }

    private record ClauseSlice(SliceKind kind, String body) {}

    private List<ClauseSlice> sliceUpdateClauses(String expr) {
        List<DynamoDbExpressionToken> tokens = new DynamoDbExpressionLexer(expr).tokenize();
        // remove EOF
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).type() == DynamoDbExpressionTokenType.EOF) {
            tokens = tokens.subList(0, tokens.size() - 1);
        }
        List<ClauseSlice> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            DynamoDbExpressionToken t = tokens.get(i);
            SliceKind k = null;
            if (t.type() == DynamoDbExpressionTokenType.SET) {
                k = SliceKind.SET;
            } else if (t.type() == DynamoDbExpressionTokenType.REMOVE) {
                k = SliceKind.REMOVE;
            } else if (t.type() == DynamoDbExpressionTokenType.ADD) {
                k = SliceKind.ADD;
            } else if (t.type() == DynamoDbExpressionTokenType.DELETE) {
                k = SliceKind.DELETE;
            }
            if (k == null) {
                i++;
                continue;
            }
            int depth = 0;
            int start = i + 1;
            int j = start;
            while (j < tokens.size()) {
                DynamoDbExpressionToken u = tokens.get(j);
                if (u.type() == DynamoDbExpressionTokenType.LPAREN
                        || u.type() == DynamoDbExpressionTokenType.LBRACKET) {
                    depth++;
                } else if (u.type() == DynamoDbExpressionTokenType.RPAREN
                        || u.type() == DynamoDbExpressionTokenType.RBRACKET) {
                    depth = Math.max(0, depth - 1);
                }
                if (depth == 0 && isClauseStarter(u)) {
                    break;
                }
                j++;
            }
            String body = joinLexemes(tokens, start, j);
            out.add(new ClauseSlice(k, body));
            i = j;
        }
        if (out.isEmpty() && !expr.isBlank()) {
            out.add(new ClauseSlice(SliceKind.SET, expr));
        }
        return out;
    }

    private static boolean isClauseStarter(DynamoDbExpressionToken u) {
        return u.type() == DynamoDbExpressionTokenType.SET
                || u.type() == DynamoDbExpressionTokenType.REMOVE
                || u.type() == DynamoDbExpressionTokenType.ADD
                || u.type() == DynamoDbExpressionTokenType.DELETE;
    }

    private static String joinLexemes(List<DynamoDbExpressionToken> tokens, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int k = start; k < end; k++) {
            DynamoDbExpressionToken t = tokens.get(k);
            if (!sb.isEmpty() && needsSpace(sb.charAt(sb.length() - 1), t)) {
                sb.append(' ');
            }
            sb.append(t.lexeme());
        }
        return sb.toString().strip();
    }

    private static boolean needsSpace(char prevLast, DynamoDbExpressionToken next) {
        char c = next.lexeme().isEmpty() ? '_' : next.lexeme().charAt(0);
        return (Character.isLetterOrDigit(prevLast) || prevLast == '_' || prevLast == ':')
                && (Character.isLetterOrDigit(c) || c == '#' || c == '_');
    }

    private static void collectSetLhs(String body, Set<String> acc, Map<String, String> names) {
        if (body == null || body.isBlank()) {
            return;
        }
        List<DynamoDbExpressionToken> toks = new DynamoDbExpressionLexer(body).tokenize();
        if (!toks.isEmpty() && toks.get(toks.size() - 1).type() == DynamoDbExpressionTokenType.EOF) {
            toks = toks.subList(0, toks.size() - 1);
        }
        int depth = 0;
        int segStart = 0;
        for (int i = 0; i < toks.size(); i++) {
            DynamoDbExpressionToken t = toks.get(i);
            if (t.type() == DynamoDbExpressionTokenType.LPAREN) {
                depth++;
            } else if (t.type() == DynamoDbExpressionTokenType.RPAREN) {
                depth = Math.max(0, depth - 1);
            }
            boolean atTopComma = t.type() == DynamoDbExpressionTokenType.COMMA && depth == 0;
            boolean atEnd = i == toks.size() - 1;
            if (atTopComma || atEnd) {
                int endIdx = atTopComma ? i : i + 1;
                List<DynamoDbExpressionToken> slice = toks.subList(segStart, endIdx);
                String lhs = extractLhsBeforeAssign(slice);
                if (!lhs.isBlank()) {
                    acc.add(resolvePathKey(lhs, names));
                }
                segStart = i + 1;
            }
        }
    }

    private static String extractLhsBeforeAssign(List<DynamoDbExpressionToken> slice) {
        int eqDepth = 0;
        for (int i = 0; i < slice.size(); i++) {
            DynamoDbExpressionToken t = slice.get(i);
            if (t.type() == DynamoDbExpressionTokenType.LPAREN) {
                eqDepth++;
            } else if (t.type() == DynamoDbExpressionTokenType.RPAREN) {
                eqDepth = Math.max(0, eqDepth - 1);
            } else if (t.type() == DynamoDbExpressionTokenType.OPERATOR && "=".equals(t.lexeme()) && eqDepth == 0) {
                return joinLexemes(new ArrayList<>(slice.subList(0, i)), 0, i);
            }
        }
        return "";
    }

    private static void collectRemovePaths(String body, Set<String> acc, Map<String, String> names) {
        if (body == null || body.isBlank()) {
            return;
        }
        List<String> parts = splitTopLevelComma(body);
        for (String part : parts) {
            if (!part.isBlank()) {
                acc.add(resolvePathKey(part.strip(), names));
            }
        }
    }

    private static void collectSimplePaths(String body, Set<String> acc, Map<String, String> names) {
        if (body == null || body.isBlank()) {
            return;
        }
        List<DynamoDbExpressionToken> toks = new DynamoDbExpressionLexer(body).tokenize();
        if (!toks.isEmpty() && toks.get(toks.size() - 1).type() == DynamoDbExpressionTokenType.EOF) {
            toks = toks.subList(0, toks.size() - 1);
        }
        int depth = 0;
        int segStart = 0;
        for (int i = 0; i < toks.size(); i++) {
            DynamoDbExpressionToken t = toks.get(i);
            if (t.type() == DynamoDbExpressionTokenType.LPAREN) {
                depth++;
            } else if (t.type() == DynamoDbExpressionTokenType.RPAREN) {
                depth = Math.max(0, depth - 1);
            }
            boolean atTopComma = t.type() == DynamoDbExpressionTokenType.COMMA && depth == 0;
            boolean atEnd = i == toks.size() - 1;
            if (atTopComma || atEnd) {
                int endIdx = atTopComma ? i : i + 1;
                List<DynamoDbExpressionToken> slice = toks.subList(segStart, endIdx);
                String path = firstPathTokenSequence(slice);
                if (!path.isEmpty()) {
                    acc.add(resolvePathKey(path, names));
                }
                segStart = i + 1;
            }
        }
    }

    private static String firstPathTokenSequence(List<DynamoDbExpressionToken> slice) {
        int i = 0;
        while (i < slice.size()) {
            DynamoDbExpressionToken t = slice.get(i);
            if (t.type() == DynamoDbExpressionTokenType.ATTR_NAME
                    || t.type() == DynamoDbExpressionTokenType.IDENTIFIER) {
                int j = i;
                while (j < slice.size()) {
                    DynamoDbExpressionToken u = slice.get(j);
                    if (u.type() == DynamoDbExpressionTokenType.DOT
                            || u.type() == DynamoDbExpressionTokenType.ATTR_NAME
                            || u.type() == DynamoDbExpressionTokenType.IDENTIFIER
                            || u.type() == DynamoDbExpressionTokenType.LBRACKET
                            || u.type() == DynamoDbExpressionTokenType.RBRACKET
                            || u.type() == DynamoDbExpressionTokenType.NUMBER) {
                        j++;
                    } else {
                        break;
                    }
                }
                return joinLexemes(slice, i, j);
            }
            i++;
        }
        return "";
    }

    private static String resolvePathKey(String rawPath, Map<String, String> names) {
        try {
            Expr e = parsePathExprOnly(rawPath);
            if (e instanceof PathExpr p) {
                StringBuilder sb = new StringBuilder();
                for (Seg s : p.segments()) {
                    if (s instanceof NameSeg ns) {
                        if (ns.placeholder()) {
                            String attr = names.get(ns.rawLexeme());
                            sb.append(attr != null ? attr : ns.rawLexeme());
                        } else {
                            sb.append(ns.rawLexeme());
                        }
                    } else if (s instanceof IndexSeg ix) {
                        sb.append('[').append(ix.index()).append(']');
                    }
                    sb.append('.');
                }
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '.') {
                    sb.setLength(sb.length() - 1);
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return rawPath.replace(" ", "");
    }

    private void checkUpdateIfNotExists(List<ClauseSlice> clauses, List<SemanticError> out) {
        for (ClauseSlice sl : clauses) {
            if (sl.kind() == SliceKind.SET) {
                continue;
            }
            String b = sl.body();
            if (b == null) {
                continue;
            }
            String lower = b.toLowerCase(Locale.ROOT);
            if (lower.contains("if_not_exists")) {
                out.add(new SemanticError(
                        "DDB-UE-IFNE-WHERE",
                        msg("if_not_exists() can only be used in SET expressions",
                                "if_not_exists() solo puede usarse en expresiones SET"),
                        msg("Move if_not_exists into a SET clause.",
                                "Mueva if_not_exists a una cláusula SET."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private void warnListAppendNonList(
            DynamoDbExpressionPayload p,
            List<ClauseSlice> clauses,
            List<SemanticError> out) {
        for (ClauseSlice sl : clauses) {
            if (sl.kind() != SliceKind.SET) {
                continue;
            }
            try {
                Expr ast = parseExpr(sl.body());
                List<CallExpr> apps = new ArrayList<>();
                collectCalls(ast, "list_append", apps);
                for (CallExpr c : apps) {
                    List<Expr> args = c.args();
                    if (args.size() >= 2) {
                        boolean rhsList = looksLikeListPlaceholder(args.get(1), p.expressionAttributeValues());
                        if (!rhsList) {
                            out.add(new SemanticError(
                                    "DDB-UE-LAPPEND",
                                    msg("list_append() expects both arguments to be lists",
                                            "list_append() espera que ambos argumentos sean listas"),
                                    msg("Ensure the second operand is a list literal placeholder mapped in expressionAttributeValues.",
                                            "Asegúrese de que el segundo operando sea un placeholder de lista mapeado en expressionAttributeValues."),
                                    SemanticError.Severity.WARNING));
                        }
                    }
                }
            } catch (Exception ignored) {
                // ignore parse errors here
            }
        }
    }

    private static boolean looksLikeListPlaceholder(Expr e, Map<String, JsonNode> valueMap) {
        Expr u = unwrap(e);
        if (u instanceof ValueExpr v) {
            JsonNode node = valueMap != null ? valueMap.get(v.placeholder()) : null;
            if (node != null && node.isArray()) {
                return true;
            }
            String name = v.placeholder().toLowerCase(Locale.ROOT);
            return name.contains("list") || name.contains("arr");
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // CONDITION
    // -------------------------------------------------------------------------

    private void analyzeCondition(DynamoDbExpressionPayload p, List<SemanticError> out) {
        Expr ast = parseExpr(p.expression());
        checkFunctionArity(ast, out, false);
        checkTautology(ast, out);
        checkCondDeprecatedOrUnsupportedFunctions(ast, out);
        checkCondAttributeExistsHasPath(ast, out);
        checkCondSizeOnNumericAttribute(ast, p, out);
        String pk = p.partitionKeyAttributeName();
        if (pk != null && !pk.isBlank()) {
            warnAttrExistsOnPk(ast, p, pk, out);
        }
    }

    /**
     * DDB-COND-001 — {@code if_not_exists} / {@code list_append} belong to UpdateExpression, not
     * ConditionExpression; using them here is unsupported and behaves like legacy misuse.
     */
    private void checkCondDeprecatedOrUnsupportedFunctions(Expr root, List<SemanticError> out) {
        List<CallExpr> ifne = new ArrayList<>();
        collectCalls(root, "if_not_exists", ifne);
        List<CallExpr> lapp = new ArrayList<>();
        collectCalls(root, "list_append", lapp);
        if (ifne.isEmpty() && lapp.isEmpty()) {
            return;
        }
        out.add(new SemanticError(
                "DDB-COND-001",
                msg("ConditionExpression uses UpdateExpression-only functions (deprecated pattern for conditions)",
                        "ConditionExpression usa funciones propias de UpdateExpression (patrón no admitido en condiciones)"),
                msg("Remove if_not_exists / list_append from ConditionExpression; use TransactWriteItems checks or UpdateExpression.",
                        "Quite if_not_exists / list_append de ConditionExpression; use comprobaciones en TransactWriteItems o UpdateExpression."),
                SemanticError.Severity.WARNING));
    }

    /** DDB-COND-002 — attribute_exists must receive an attribute path, not a value placeholder. */
    private void checkCondAttributeExistsHasPath(Expr root, List<SemanticError> out) {
        List<CallExpr> calls = new ArrayList<>();
        collectCalls(root, "attribute_exists", calls);
        for (CallExpr c : calls) {
            if (c.args().isEmpty()) {
                out.add(new SemanticError(
                        "DDB-COND-002",
                        msg("attribute_exists requires an attribute path",
                                "attribute_exists requiere una ruta de atributo"),
                        msg("Use attribute_exists(#name) or attribute_exists(attr) with a path token, not a bare value.",
                            "Use attribute_exists(#nombre) o attribute_exists(attr) con ruta, no un valor suelto."),
                        SemanticError.Severity.ERROR));
                continue;
            }
            Expr a0 = unwrap(c.args().get(0));
            if (!(a0 instanceof PathExpr)) {
                out.add(new SemanticError(
                        "DDB-COND-002",
                        msg("attribute_exists requires an attribute name / path as its argument",
                                "attribute_exists requiere como argumento el nombre o la ruta del atributo"),
                        msg("Pass a path such as #pk or status, not a value placeholder like :v.",
                            "Pase una ruta como #pk o status, no un placeholder de valor como :v."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    /**
     * DDB-COND-003 — DynamoDB does not define size() for Number ({@code N}) / Number Set ({@code NS})
     * attributes; only String, Binary, Set, List, and Map.
     */
    private void checkCondSizeOnNumericAttribute(Expr root, DynamoDbExpressionPayload p, List<SemanticError> out) {
        Map<String, String> types = p.attributeTypes();
        if (types == null || types.isEmpty()) {
            return;
        }
        List<CallExpr> sizes = new ArrayList<>();
        collectCalls(root, "size", sizes);
        for (CallExpr c : sizes) {
            if (c.args().isEmpty()) {
                continue;
            }
            PathLike pl = pathLike(unwrap(c.args().get(0)));
            if (pl == null) {
                continue;
            }
            String logical = firstPathLogicalName(pl, p.expressionAttributeNames());
            if (logical == null || logical.isBlank()) {
                continue;
            }
            String ty = lookupAttributeType(types, logical);
            if (ty == null) {
                continue;
            }
            if ("N".equals(ty) || "NS".equals(ty)) {
                out.add(new SemanticError(
                        "DDB-COND-003",
                        msg("size() is not defined for Number (N) or Number Set (NS) attributes in DynamoDB",
                                "size() no está definido para atributos N o NS en DynamoDB"),
                        msg("Compare numeric attributes with =, <>, <, etc., or store counts in a separate attribute.",
                            "Compare atributos numéricos con =, <>, <, etc., o guarde contadores en otro atributo."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private static String firstPathLogicalName(PathLike pl, Map<String, String> names) {
        if (pl == null || pl.segments().isEmpty()) {
            return null;
        }
        Seg s0 = pl.segments().get(0);
        if (!(s0 instanceof NameSeg ns)) {
            return null;
        }
        if (ns.placeholder()) {
            String key = ns.rawLexeme();
            if (names != null && key != null && names.containsKey(key)) {
                return names.get(key);
            }
            return null;
        }
        return ns.rawLexeme();
    }

    private static String lookupAttributeType(Map<String, String> types, String logical) {
        if (logical == null) {
            return null;
        }
        String u = logical.trim();
        String direct = types.get(u);
        if (direct != null && !direct.isBlank()) {
            return direct.trim().toUpperCase(Locale.ROOT);
        }
        for (Map.Entry<String, String> e : types.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(u)) {
                return e.getValue() != null ? e.getValue().trim().toUpperCase(Locale.ROOT) : null;
            }
        }
        return null;
    }

    private void checkTautology(Expr root, List<SemanticError> out) {
        if (root == null) {
            return;
        }
        if (root instanceof ComparisonExpr c && "=".equals(c.op())) {
            Expr l = unwrap(c.left());
            Expr r = unwrap(c.right());
            if (l instanceof ValueExpr lv && r instanceof ValueExpr rv
                    && Objects.equals(lv.placeholder(), rv.placeholder())) {
                out.add(new SemanticError(
                        "DDB-CE-TAUTO",
                        msg("Condition always evaluates to true; this will never prevent the write",
                                "La condición siempre es verdadera; nunca evitará la escritura"),
                        msg("Compare against a distinct value or use attribute_exists / size checks.",
                                "Compare con un valor distinto o use comprobaciones attribute_exists / size."),
                        SemanticError.Severity.WARNING));
                return;
            }
        }
        if (root instanceof OrExpr o) {
            checkTautology(o.left(), out);
            checkTautology(o.right(), out);
        } else if (root instanceof AndExpr a) {
            checkTautology(a.left(), out);
            checkTautology(a.right(), out);
        } else if (root instanceof NotExpr n) {
            checkTautology(n.inner(), out);
        } else if (root instanceof ParenExpr p) {
            checkTautology(p.inner(), out);
        } else if (root instanceof BetweenExpr b) {
            checkTautology(b.path(), out);
            checkTautology(b.low(), out);
            checkTautology(b.high(), out);
        } else if (root instanceof InExpr i) {
            checkTautology(i.path(), out);
            for (Expr v : i.values()) {
                checkTautology(v, out);
            }
        } else if (root instanceof CallExpr c) {
            for (Expr arg : c.args()) {
                checkTautology(arg, out);
            }
        }
    }

    private void warnAttrExistsOnPk(
            Expr root,
            DynamoDbExpressionPayload p,
            String pkAttr,
            List<SemanticError> out) {
        Set<String> holders = placeholdersForAttribute(p.expressionAttributeNames(), pkAttr);
        List<CallExpr> calls = new ArrayList<>();
        collectCalls(root, "attribute_exists", calls);
        for (CallExpr c : calls) {
            if (c.args().isEmpty()) {
                continue;
            }
            PathLike pl = pathLike(c.args().get(0));
            if (pl != null && pathTargetsAttribute(pl, pkAttr, holders, p.expressionAttributeNames())) {
                out.add(new SemanticError(
                        "DDB-CE-PK-EXISTS",
                        msg("Primary key always exists in DynamoDB; attribute_exists on PK is redundant",
                                "La clave primaria siempre existe en DynamoDB; attribute_exists en PK es redundante"),
                        msg("You can remove attribute_exists for the primary key.",
                                "Puede eliminar attribute_exists para la clave primaria."),
                        SemanticError.Severity.INFO));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Parser
    // -------------------------------------------------------------------------

    private static Expr parseExpr(String input) {
        List<DynamoDbExpressionToken> tokens = new DynamoDbExpressionLexer(input).tokenize();
        Parser p = new Parser(tokens);
        Expr e = p.parseExpression();
        p.expect(DynamoDbExpressionTokenType.EOF);
        return e;
    }

    private static Expr parsePathExprOnly(String input) {
        List<DynamoDbExpressionToken> tokens = new DynamoDbExpressionLexer(input).tokenize();
        Parser p = new Parser(tokens);
        Expr e = p.parsePathWithSuffixes(p.parsePathPrimary());
        p.expect(DynamoDbExpressionTokenType.EOF);
        return e;
    }

    private static final class Parser {
        private final List<DynamoDbExpressionToken> tokens;
        private int pos;

        Parser(List<DynamoDbExpressionToken> tokens) {
            this.tokens = tokens;
            this.pos = 0;
        }

        DynamoDbExpressionToken peek() {
            return tokens.get(pos);
        }

        void advance() {
            if (peek().type() != DynamoDbExpressionTokenType.EOF) {
                pos++;
            }
        }

        boolean match(DynamoDbExpressionTokenType t) {
            if (peek().type() == t) {
                advance();
                return true;
            }
            return false;
        }

        void expect(DynamoDbExpressionTokenType t) {
            if (!match(t)) {
                throw new ParseException("Expected " + t + " but found " + peek().type());
            }
        }

        Expr parseExpression() {
            return parseOr();
        }

        Expr parseOr() {
            Expr x = parseAnd();
            while (match(DynamoDbExpressionTokenType.OR)) {
                Expr y = parseAnd();
                x = new OrExpr(x, y);
            }
            return x;
        }

        Expr parseAnd() {
            Expr x = parseUnary();
            while (match(DynamoDbExpressionTokenType.AND)) {
                Expr y = parseUnary();
                x = new AndExpr(x, y);
            }
            return x;
        }

        Expr parseUnary() {
            if (match(DynamoDbExpressionTokenType.NOT)) {
                return new NotExpr(parseUnary());
            }
            return parseComparisonStart();
        }

        Expr parseComparisonStart() {
            Expr left = parseOperand();
            if (match(DynamoDbExpressionTokenType.BETWEEN)) {
                Expr lo = parseOperand();
                expect(DynamoDbExpressionTokenType.AND);
                Expr hi = parseOperand();
                return new BetweenExpr(left, lo, hi);
            }
            if (match(DynamoDbExpressionTokenType.IN)) {
                expect(DynamoDbExpressionTokenType.LPAREN);
                List<Expr> vals = new ArrayList<>();
                if (peek().type() != DynamoDbExpressionTokenType.RPAREN) {
                    vals.add(parseOperand());
                    while (match(DynamoDbExpressionTokenType.COMMA)) {
                        vals.add(parseOperand());
                    }
                }
                expect(DynamoDbExpressionTokenType.RPAREN);
                return new InExpr(left, vals);
            }
            if (peek().type() == DynamoDbExpressionTokenType.OPERATOR) {
                String op = peek().lexeme();
                advance();
                Expr right = parseOperand();
                return new ComparisonExpr(left, op, right);
            }
            return left;
        }

        Expr parseOperand() {
            if (match(DynamoDbExpressionTokenType.LPAREN)) {
                Expr inner = parseExpression();
                expect(DynamoDbExpressionTokenType.RPAREN);
                return new ParenExpr(inner);
            }
            if (peek().type() == DynamoDbExpressionTokenType.ATTR_VALUE) {
                String ph = peek().lexeme();
                advance();
                return new ValueExpr(ph);
            }
            if (peek().type() == DynamoDbExpressionTokenType.IDENTIFIER) {
                String id = peek().lexeme();
                if (isFunctionName(id) && lookaheadIsParen()) {
                    return parseCall();
                }
            }
            if (peek().type() == DynamoDbExpressionTokenType.IDENTIFIER
                    || peek().type() == DynamoDbExpressionTokenType.ATTR_NAME) {
                return parsePathWithSuffixes(parsePathPrimary());
            }
            throw new ParseException("Unexpected token " + peek().type() + " ('" + peek().lexeme() + "')");
        }

        boolean lookaheadIsParen() {
            return pos + 1 < tokens.size() && tokens.get(pos + 1).type() == DynamoDbExpressionTokenType.LPAREN;
        }

        Expr parsePathPrimary() {
            if (match(DynamoDbExpressionTokenType.ATTR_NAME)) {
                String name = tokens.get(pos - 1).lexeme();
                return new PathExpr(List.of(new NameSeg(name, true)));
            }
            if (match(DynamoDbExpressionTokenType.IDENTIFIER)) {
                String id = tokens.get(pos - 1).lexeme();
                return new PathExpr(List.of(new NameSeg(id, false)));
            }
            throw new ParseException("Expected path start");
        }

        Expr parsePathWithSuffixes(Expr start) {
            PathExpr base = asPath(start);
            List<Seg> segs = new ArrayList<>(base.segments());
            while (true) {
                if (match(DynamoDbExpressionTokenType.DOT)) {
                    if (match(DynamoDbExpressionTokenType.ATTR_NAME)) {
                        String n = tokens.get(pos - 1).lexeme();
                        segs.add(new NameSeg(n, true));
                    } else if (match(DynamoDbExpressionTokenType.IDENTIFIER)) {
                        String n = tokens.get(pos - 1).lexeme();
                        segs.add(new NameSeg(n, false));
                    } else {
                        throw new ParseException("Expected attribute segment after \".\"");
                    }
                } else if (match(DynamoDbExpressionTokenType.LBRACKET)) {
                    expect(DynamoDbExpressionTokenType.NUMBER);
                    int idx = Integer.parseInt(tokens.get(pos - 1).lexeme());
                    expect(DynamoDbExpressionTokenType.RBRACKET);
                    segs.add(new IndexSeg(idx));
                } else {
                    break;
                }
            }
            return new PathExpr(List.copyOf(segs));
        }

        PathExpr asPath(Expr e) {
            if (e instanceof PathExpr p) {
                return p;
            }
            throw new ParseException("Internal path error");
        }

        Expr parseCall() {
            String name = peek().lexeme();
            advance();
            return finishCall(name);
        }

        Expr finishCall(String name) {
            expect(DynamoDbExpressionTokenType.LPAREN);
            List<Expr> args = new ArrayList<>();
            if (peek().type() != DynamoDbExpressionTokenType.RPAREN) {
                args.add(parseOperand());
                while (match(DynamoDbExpressionTokenType.COMMA)) {
                    args.add(parseOperand());
                }
            }
            expect(DynamoDbExpressionTokenType.RPAREN);
            return new CallExpr(name, args);
        }
    }

    private static boolean isFunctionName(String id) {
        String n = id.toLowerCase(Locale.ROOT);
        return "attribute_exists".equals(n)
                || "attribute_not_exists".equals(n)
                || "attribute_type".equals(n)
                || "begins_with".equals(n)
                || "contains".equals(n)
                || "size".equals(n)
                || "if_not_exists".equals(n)
                || "list_append".equals(n);
    }
}
