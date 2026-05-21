package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.RoutineParameterSupport;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.AnalysisMessages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.IdentityHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whole-block procedural semantics on top of the AST (DECLARE / BEGIN … END and
 * {@code CREATE PROCEDURE|FUNCTION|TRIGGER} bodies). Complements per-statement checks in
 * {@link com.qwerys.qwerys_backend.analyzer.SemanticAnalyzer} with cross-statement rules.
 *
 * <p>Variable visibility uses nested scopes. Control-flow properties (terminación, inalcanzabilidad)
 * se obtienen de un <em>CFG implícito</em> modelado como recorrido sobre listas secuenciales,
 * ramas IF/CASE y bucles — sin materializar un grafo aparte para el motor actual.
 */
public final class ProceduralSemanticAnalyzer {

    private static final Pattern PROC_ID =
            Pattern.compile("\\b(@?[A-Za-z_][\\w]*)\\b");
    private static final Pattern BEFORE_FIRST_SELF_CALL_HAS_IF =
            Pattern.compile("\\bIF\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_SUBPROGRAM_NAME =
            Pattern.compile(
                    "(?is)\\bCREATE\\s+(?:OR\\s+(?:REPLACE|ALTER)\\s+)?(?:PROCEDURE|FUNCTION)\\s+"
                            + "(?:[\"\\w]+\\.|[\"\\w]+\\.)?[\"']?([A-Za-z_][\\w]*)[\"']?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern PROC_TAUTOLOGY =
            Pattern.compile("\\b1\\s*=\\s*1\\b|\\bTRUE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_COMPARE =
            Pattern.compile(
                    "\\b(@?[A-Za-z_]\\w*)\\s*(?:=|!=|<>|<=|>=|<|>)\\s*\\1\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern RETURNS_TAIL =
            Pattern.compile("\\bRETURNS\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("^\\s*'(?:[^']|'')*'\\s*$");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("^\\s*-?\\d+(?:\\.\\d+)?\\s*$");

    private static final Set<String> STOPWORDS = buildStopwords();

    private final List<SemanticError> findings = new ArrayList<>();
    private final AstNode astRoot;
    private final String rawSql;
    private final Locale uiLocale;

    /** Mapa nombre canónico (por ámbito) → metadatos de declaración. */
    private final Map<String, VarDecl> varRegistry = new LinkedHashMap<>();
    private int declCounter;

    /** Ámbito sintáctico por nodo de bloque (mismo orden en pases DECLARE / efectos). */
    private final IdentityHashMap<AstNode, Integer> blockScopeIds = new IdentityHashMap<>();
    private int nextBlockScopeId;

    /** Por clave {@code scopeId:CANON}. */
    private final Map<String, VarStats> stats = new HashMap<>();

    /**
     * Grafo de flujo simplificado (etiquetas simbólicas → sucesores). Se rellena durante el
     * análisis de listas para inspección o extensiones futuras.
     */
    private final Map<String, List<String>> cfgSuccessors = new LinkedHashMap<>();
    private int cfgNodeSeq;

    private ProceduralSemanticAnalyzer(AstNode astRoot, String rawSql, Locale uiLocale) {
        this.astRoot = astRoot;
        this.rawSql = rawSql;
        this.uiLocale = uiLocale != null ? uiLocale : Locale.ENGLISH;
    }

    private String t(String en, String es) {
        return AnalysisMessages.t(uiLocale, en, es);
    }

    /**
     * Ejecuta reglas PROC-SEM-001 … PROC-SEM-010 sobre el bloque o unidad completa.
     *
     * @param astRoot raíz {@link AstNode} (p. ej. {@code BLOCK_STATEMENT},
     *                {@code CREATE_PROCEDURE_STATEMENT}, {@code CREATE_FUNCTION_STATEMENT},
     *                {@code CREATE_TRIGGER_STATEMENT})
     * @param rawSql  SQL original (recursión, firmas); puede ser {@code null}
     * @return hallazgos nuevos; nunca {@code null}
     */
    public static List<SemanticError> analyze(AstNode astRoot, String rawSql) {
        return analyze(astRoot, rawSql, Locale.ENGLISH);
    }

    /**
     * Same as {@link #analyze(AstNode, String)} with UI locale for bilingual messages.
     */
    public static List<SemanticError> analyze(AstNode astRoot, String rawSql, Locale uiLocale) {
        if (astRoot == null) {
            return List.of();
        }
        ProceduralSemanticAnalyzer a = new ProceduralSemanticAnalyzer(astRoot, rawSql, uiLocale);
        a.run();
        return List.copyOf(a.findings);
    }

    private void run() {
        AstNode block = primaryExecutableBlock(astRoot);
        if (block == null) {
            return;
        }

        nextBlockScopeId = 0;
        blockScopeIds.clear();
        registerFormalParametersFromCreate();
        DeclWalker dw = new DeclWalker();
        dw.walkBlock(block);

        stats.clear();
        for (VarDecl d : varRegistry.values()) {
            stats.put(statsKey(d.scopeId, d.canonicalName), new VarStats());
        }

        EffectWalker ew = new EffectWalker();
        ew.walkBlock(block);

        for (VarDecl d : varRegistry.values()) {
            String sk = statsKey(d.scopeId, d.canonicalName);
            VarStats st = stats.get(sk);
            if (st == null) {
                continue;
            }
            if (st.assigned && !st.readInExpr) {
                findings.add(new SemanticError(
                        "PROC-SEM-001",
                        t("Variable assigned but never read: " + d.sourceName(),
                                "Variable asignada pero nunca leída: " + d.sourceName()),
                        t("Remove the assignment if it is unnecessary, or use the value in a condition, "
                                        + "RETURN, or another expression.",
                                "Elimine la asignación si es innecesaria o use el valor en una condición, "
                                        + "RETURN u otra expresión."),
                        SemanticError.Severity.WARNING));
            }
            if (st.readInExpr && !st.assigned && !d.hasDefault && !"formal".equals(d.scope())) {
                findings.add(new SemanticError(
                        "PROC-SEM-002",
                        t("Variable read without being assigned after DECLARE: " + d.sourceName(),
                                "Variable leída sin haber sido asignada tras DECLARE: " + d.sourceName()),
                        t("Initialize it in DECLARE (DEFAULT/:=), assign it before first use, or review control flow.",
                                "Inicialícela en DECLARE (DEFAULT/:=), asígnela antes del primer uso o revise el flujo."),
                        SemanticError.Severity.WARNING));
            }
        }

        cfgNodeSeq = 0;
        cfgSuccessors.clear();
        String entry = nextCfgId("entry");
        String bodyExit = analyzeStatementListControlFlow(
                findChild(block, "STATEMENT_LIST"), entry, "body-exit");

        connectCfg(entry, bodyExit);
        reportUnreachableInList(findChild(block, "STATEMENT_LIST"));

        AstNode ex = findChild(block, "EXCEPTION_BLOCK");
        if (ex != null) {
            for (AstNode h : ex.getChildren()) {
                if ("EXCEPTION_HANDLER".equals(h.getNodeType())) {
                    AstNode sl = findChild(h, "STATEMENT_LIST");
                    reportUnreachableInList(sl);
                }
            }
        }

        if ("CREATE_FUNCTION_STATEMENT".equals(astRoot.getNodeType())) {
            AstNode mainList = findChild(block, "STATEMENT_LIST");
            if (stmtListMayCompleteNormally(mainList)) {
                findings.add(new SemanticError(
                        "PROC-SEM-003",
                        t("Possible code path without RETURN at end of function",
                                "Posible camino sin RETURN al final de la función"),
                        t("Ensure all branches return a value (RETURN) or raise an exception.",
                                "Asegúrese de que todas las ramas devuelven un valor (RETURN) o lanzan excepción."),
                        SemanticError.Severity.ERROR));
            }
        }

        checkWhileInfiniteLoops(block);
        checkCursorBranchLeak(astRoot);
        walkSelfComparisons(block);
        checkReturnTypeCompatibility(astRoot);
        checkInParameterMutations(astRoot, block);
        maybeWarnRecursionWithoutBase();
    }

    private void registerFormalParametersFromCreate() {
        if (!"CREATE_PROCEDURE_STATEMENT".equals(astRoot.getNodeType())
                && !"CREATE_FUNCTION_STATEMENT".equals(astRoot.getNodeType())) {
            return;
        }
        AstNode pl = findChild(astRoot, "PARAMETER_LIST");
        int outerSid = 0;
        for (Map.Entry<String, String> e : RoutineParameterSupport.formalNamesWithDisplay(pl).entrySet()) {
            String canon = e.getKey();
            String key = outerSid + ":" + canon;
            if (varRegistry.containsKey(key)) {
                continue;
            }
            VarDecl d = new VarDecl(e.getValue(), canon, "", "formal", outerSid, declCounter++, false);
            varRegistry.put(key, d);
        }
    }

    // -------------------------------------------------------------------------
    // Variable map + dataflow
    // -------------------------------------------------------------------------

    private final class DeclWalker {
        void walkBlock(AstNode block) {
            int sid = nextBlockScopeId++;
            blockScopeIds.put(block, sid);
            AstNode declSec = findChild(block, "DECLARE_SECTION");
            if (declSec != null) {
                for (AstNode ch : declSec.getChildren()) {
                    if ("VARIABLE_DECLARATION".equals(ch.getNodeType())
                            && ch.getValue() != null) {
                        registerVar(ch, sid);
                    }
                }
            }
            walkStmtListForNestedBlocks(findChild(block, "STATEMENT_LIST"));
            AstNode ex = findChild(block, "EXCEPTION_BLOCK");
            if (ex != null) {
                for (AstNode h : ex.getChildren()) {
                    if ("EXCEPTION_HANDLER".equals(h.getNodeType())) {
                        walkStmtListForNestedBlocks(findChild(h, "STATEMENT_LIST"));
                    }
                }
            }
        }

        void walkStmtListForNestedBlocks(AstNode list) {
            if (list == null) {
                return;
            }
            for (AstNode s : list.getChildren()) {
                walkStmtNested(s);
            }
        }

        void walkStmtNested(AstNode stmt) {
            switch (stmt.getNodeType()) {
                case "BLOCK_STATEMENT" -> walkBlock(stmt);
                case "IF_STATEMENT" -> {
                    for (AstNode c : stmt.getChildren()) {
                        switch (c.getNodeType()) {
                            case "STATEMENT_LIST" -> walkStmtListForNestedBlocks(c);
                            case "ELSIF_BRANCH", "ELSE_BLOCK" -> {
                                for (AstNode x : c.getChildren()) {
                                    if ("STATEMENT_LIST".equals(x.getNodeType())) {
                                        walkStmtListForNestedBlocks(x);
                                    }
                                }
                            }
                            default -> { }
                        }
                    }
                }
                case "CASE_STATEMENT" -> {
                    for (AstNode c : stmt.getChildren()) {
                        if ("CASE_BRANCH".equals(c.getNodeType())
                                || "ELSE_BLOCK".equals(c.getNodeType())) {
                            for (AstNode x : c.getChildren()) {
                                if ("STATEMENT_LIST".equals(x.getNodeType())) {
                                    walkStmtListForNestedBlocks(x);
                                }
                            }
                        }
                    }
                }
                case "WHILE_STATEMENT", "LOOP_STATEMENT", "FOR_STATEMENT" -> {
                    AstNode body = findChild(stmt, "STATEMENT_LIST");
                    walkStmtListForNestedBlocks(body);
                }
                case "EXCEPTION_BLOCK" -> {
                    if ("TRY_CATCH".equals(stmt.getValue())) {
                        AstNode tryS = findChild(stmt, "TRY_SECTION");
                        if (tryS != null) {
                            walkStmtListForNestedBlocks(findChild(tryS, "STATEMENT_LIST"));
                        }
                        AstNode catchS = findChild(stmt, "CATCH_SECTION");
                        if (catchS != null) {
                            walkStmtListForNestedBlocks(findChild(catchS, "STATEMENT_LIST"));
                        }
                    }
                }
                default -> { }
            }
        }
    }

    private void registerVar(AstNode decl, int scopeId) {
        String name = decl.getValue();
        String canon = canonical(name);
        AstNode type = findChild(decl, "TYPE");
        String typeStr = type != null && type.getValue() != null ? type.getValue() : "";
        boolean hasDef = findChild(decl, "DEFAULT_VALUE") != null;
        String scopeLabel = "scope-" + scopeId;
        VarDecl d = new VarDecl(
                name,
                canon,
                typeStr,
                scopeLabel,
                scopeId,
                declCounter++,
                hasDef);
        String key = scopeId + ":" + canon;
        if (varRegistry.containsKey(key)) {
            VarDecl existing = varRegistry.get(key);
            if ("formal".equals(existing.scope())) {
                return;
            }
        }
        varRegistry.put(key, d);
    }

    private final class EffectWalker {
        /** Variables visibles por marco de bloque (DECLARE); resolución de más interno a externo. */
        private final List<Map<String, VarDecl>> scopeFrames = new ArrayList<>();

        void walkBlock(AstNode block) {
            Integer sidObj = blockScopeIds.get(block);
            int sid = sidObj != null ? sidObj : -1;
            Map<String, VarDecl> frame = new HashMap<>();
            if (sid >= 0) {
                for (VarDecl vd : varRegistry.values()) {
                    if (vd.scopeId == sid) {
                        frame.put(vd.canonicalName, vd);
                    }
                }
            }
            scopeFrames.add(frame);
            AstNode declSec = findChild(block, "DECLARE_SECTION");
            if (declSec != null && sid >= 0) {
                for (AstNode ch : declSec.getChildren()) {
                    if ("VARIABLE_DECLARATION".equals(ch.getNodeType())) {
                        AstNode def = findChild(ch, "DEFAULT_VALUE");
                        if (def != null) {
                            markReads(findChild(def, "EXPRESSION"));
                        }
                    }
                }
            }
            walkList(findChild(block, "STATEMENT_LIST"));
            AstNode ex = findChild(block, "EXCEPTION_BLOCK");
            if (ex != null) {
                for (AstNode h : ex.getChildren()) {
                    if ("EXCEPTION_HANDLER".equals(h.getNodeType())) {
                        walkList(findChild(h, "STATEMENT_LIST"));
                    }
                }
            }
            scopeFrames.remove(scopeFrames.size() - 1);
        }

        void walkList(AstNode list) {
            if (list == null) {
                return;
            }
            for (AstNode s : list.getChildren()) {
                walkStmt(s);
            }
        }

        void walkStmt(AstNode stmt) {
            switch (stmt.getNodeType()) {
                case "SET_STATEMENT", "ASSIGNMENT_STATEMENT" -> {
                    AstNode target = findChild(stmt, "TARGET");
                    AstNode expr = findChild(stmt, "EXPRESSION");
                    markAssigned(target);
                    markReads(expr);
                }
                case "FETCH_STATEMENT" -> {
                    for (AstNode t : stmt.getChildren()) {
                        if ("TARGET".equals(t.getNodeType())) {
                            markAssigned(t);
                        }
                    }
                }
                case "IF_STATEMENT" -> walkIf(stmt);
                case "CASE_STATEMENT" -> walkCase(stmt);
                case "WHILE_STATEMENT", "LOOP_STATEMENT", "FOR_STATEMENT" -> {
                    AstNode cond = findChild(stmt, "CONDITION");
                    if (cond != null) {
                        AstNode ex = findChild(cond, "EXPRESSION");
                        markReads(ex);
                    }
                    AstNode fo = stmt;
                    if ("FOR_STATEMENT".equals(stmt.getNodeType())) {
                        AstNode range = findChild(stmt, "FOR_RANGE");
                        if (range != null) {
                            for (AstNode p : range.getChildren()) {
                                markReads(p);
                            }
                        }
                    }
                    walkList(findChild(fo, "STATEMENT_LIST"));
                }
                case "EXIT_WHEN_STATEMENT" -> {
                    AstNode cond = findChild(stmt, "CONDITION");
                    markReads(findChild(cond, "EXPRESSION"));
                }
                case "RETURN_STATEMENT" -> {
                    if (stmt.getChildren() != null && !stmt.getChildren().isEmpty()) {
                        for (AstNode ch : stmt.getChildren()) {
                            if ("EXPRESSION".equals(ch.getNodeType())) {
                                markReads(ch);
                            }
                        }
                    }
                }
                case "RETURN_QUERY_STATEMENT", "RETURN_NEXT_STATEMENT" -> {
                    for (AstNode ch : stmt.getChildren()) {
                        markReads(ch);
                    }
                }
                case "RAISE_STATEMENT" -> {
                    for (AstNode ch : stmt.getChildren()) {
                        if (ch.getValue() != null) {
                            markReads(new AstNode("EXPRESSION", ch.getValue()));
                        }
                    }
                }
                case "RAW_STATEMENT", "EXECUTE_STATEMENT", "PERFORM_STATEMENT" -> {
                    AstNode ex = findChild(stmt, "EXPRESSION");
                    markReads(ex);
                }
                case "BLOCK_STATEMENT" -> walkBlock(stmt);
                case "EXCEPTION_BLOCK" -> {
                    if ("TRY_CATCH".equals(stmt.getValue())) {
                        AstNode tryS = findChild(stmt, "TRY_SECTION");
                        if (tryS != null) {
                            walkList(findChild(tryS, "STATEMENT_LIST"));
                        }
                        AstNode catchS = findChild(stmt, "CATCH_SECTION");
                        if (catchS != null) {
                            walkList(findChild(catchS, "STATEMENT_LIST"));
                        }
                    }
                }
                default -> { }
            }
        }

        void walkIf(AstNode ifStmt) {
            for (AstNode c : ifStmt.getChildren()) {
                if ("CONDITION".equals(c.getNodeType())) {
                    markReads(findChild(c, "EXPRESSION"));
                } else if ("STATEMENT_LIST".equals(c.getNodeType())) {
                    walkList(c);
                } else if ("ELSIF_BRANCH".equals(c.getNodeType()) || "ELSE_BLOCK".equals(c.getNodeType())) {
                    for (AstNode x : c.getChildren()) {
                        if ("CONDITION".equals(x.getNodeType())) {
                            markReads(findChild(x, "EXPRESSION"));
                        } else if ("STATEMENT_LIST".equals(x.getNodeType())) {
                            walkList(x);
                        }
                    }
                }
            }
        }

        void walkCase(AstNode cas) {
            for (AstNode ch : cas.getChildren()) {
                if ("CASE_DISCRIMINANT".equals(ch.getNodeType())) {
                    markReads(findChild(ch, "EXPRESSION"));
                } else if ("CASE_BRANCH".equals(ch.getNodeType())
                        || "ELSE_BLOCK".equals(ch.getNodeType())) {
                    for (AstNode p : ch.getChildren()) {
                        if ("CONDITION".equals(p.getNodeType())) {
                            markReads(findChild(p, "EXPRESSION"));
                        } else if ("STATEMENT_LIST".equals(p.getNodeType())) {
                            walkList(p);
                        }
                    }
                }
            }
        }

        void markAssigned(AstNode target) {
            if (target == null || target.getValue() == null) {
                return;
            }
            VarDecl d = resolveDeclaredVar(canonical(target.getValue()));
            if (d != null) {
                VarStats st = stats.get(statsKey(d.scopeId, d.canonicalName));
                if (st != null) {
                    st.assigned = true;
                }
            }
        }

        void markReads(AstNode expr) {
            if (expr == null || expr.getValue() == null) {
                return;
            }
            for (String id : extractIds(expr.getValue())) {
                VarDecl d = resolveDeclaredVar(id);
                if (d != null) {
                    VarStats st = stats.get(statsKey(d.scopeId, d.canonicalName));
                    if (st != null) {
                        st.readInExpr = true;
                    }
                }
            }
        }

        VarDecl resolveDeclaredVar(String canon) {
            for (int i = scopeFrames.size() - 1; i >= 0; i--) {
                VarDecl d = scopeFrames.get(i).get(canon);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
    }

    private record VarDecl(
            String rawName,
            String canonicalName,
            String sqlType,
            String scope,
            int scopeId,
            int declOrder,
            boolean hasDefault) {
        String sourceName() {
            return rawName;
        }
    }

    private static final class VarStats {
        boolean assigned;
        boolean readInExpr;
    }

    private static String statsKey(int scopeId, String canon) {
        return scopeId + ":" + canon;
    }

    // -------------------------------------------------------------------------
    // Control flow (implicit CFG) & unreachable
    // -------------------------------------------------------------------------

    private String nextCfgId(String hint) {
        return hint + "-" + (cfgNodeSeq++);
    }

    private void connectCfg(String from, String to) {
        cfgSuccessors.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
    }

    private String analyzeStatementListControlFlow(AstNode list, String pred, String exitHint) {
        if (list == null || list.getChildren().isEmpty()) {
            String e = nextCfgId(exitHint);
            connectCfg(pred, e);
            return e;
        }
        String cur = pred;
        for (AstNode stmt : list.getChildren()) {
            String n = nextCfgId("stmt");
            connectCfg(cur, n);
            cur = analyzeStmtControlFlow(stmt, n);
        }
        String exit = nextCfgId(exitHint);
        connectCfg(cur, exit);
        return exit;
    }

    private String analyzeStmtControlFlow(AstNode stmt, String pred) {
        return switch (stmt.getNodeType()) {
            case "IF_STATEMENT" -> {
                String head = nextCfgId("if");
                connectCfg(pred, head);
                String merge = nextCfgId("if-merge");
                List<String> ends = new ArrayList<>();
                AstNode mainThen = findChild(stmt, "STATEMENT_LIST");
                String th = analyzeStatementListControlFlow(mainThen, head, "then");
                ends.add(th);
                for (AstNode c : stmt.getChildren()) {
                    if ("ELSIF_BRANCH".equals(c.getNodeType())) {
                        String eh = nextCfgId("elsif");
                        connectCfg(head, eh);
                        AstNode sl = findChild(c, "STATEMENT_LIST");
                        ends.add(analyzeStatementListControlFlow(sl, eh, "elsif-body"));
                    }
                }
                AstNode elseBlk = findChild(stmt, "ELSE_BLOCK");
                if (elseBlk != null) {
                    String eh = nextCfgId("else");
                    connectCfg(head, eh);
                    AstNode sl = findChild(elseBlk, "STATEMENT_LIST");
                    ends.add(analyzeStatementListControlFlow(sl, eh, "else-body"));
                } else {
                    connectCfg(head, merge);
                }
                for (String e : ends) {
                    connectCfg(e, merge);
                }
                yield merge;
            }
            case "WHILE_STATEMENT", "FOR_STATEMENT", "LOOP_STATEMENT" -> {
                String loopHead = nextCfgId("loop");
                connectCfg(pred, loopHead);
                AstNode body = findChild(stmt, "STATEMENT_LIST");
                String bodyEnd = analyzeStatementListControlFlow(body, loopHead, "loop-body");
                connectCfg(bodyEnd, loopHead);
                String after = nextCfgId("after-loop");
                connectCfg(loopHead, after);
                yield after;
            }
            case "BLOCK_STATEMENT" -> {
                AstNode sl = findChild(stmt, "STATEMENT_LIST");
                yield analyzeStatementListControlFlow(sl, pred, "inner-block");
            }
            default -> {
                String n = nextCfgId("step");
                connectCfg(pred, n);
                yield n;
            }
        };
    }

    private void reportUnreachableInList(AstNode list) {
        if (list == null) {
            return;
        }
        boolean dead = false;
        for (AstNode stmt : list.getChildren()) {
            if (dead) {
                findings.add(new SemanticError(
                        "PROC-SEM-004",
                        t("Unreachable code after RETURN/RAISE (or other unconditional exit)",
                                "Código inalcanzable tras RETURN/RAISE (u otra salida incondicional)"),
                        t("Remove dead code or move logic before the exit.",
                                "Elimine el código muerto o mueva la lógica antes de la salida."),
                        SemanticError.Severity.WARNING));
            }
            if (unconditionalAbruptExit(stmt)) {
                dead = true;
            }
        }
    }

    private static boolean unconditionalAbruptExit(AstNode stmt) {
        return switch (stmt.getNodeType()) {
            case "RETURN_STATEMENT", "RETURN_QUERY_STATEMENT", "RETURN_NEXT_STATEMENT" -> true;
            case "THROW_STATEMENT" -> true;
            case "SIGNAL_STATEMENT", "RESIGNAL_STATEMENT" -> true;
            case "RAISE_STATEMENT" -> !isBareReraise(stmt);
            default -> false;
        };
    }

    private static boolean isBareReraise(AstNode node) {
        boolean hasNamed = findChild(node, "EXCEPTION_NAME") != null;
        boolean hasApp = findChild(node, "APPLICATION_ERROR_INVOCATION") != null;
        boolean hasKind = findChild(node, "RAISE_KIND") != null;
        boolean hasMsg = findChild(node, "MESSAGE") != null;
        boolean empty = node.getValue() == null || node.getValue().isBlank();
        return empty && !hasNamed && !hasApp && !hasKind && !hasMsg;
    }

    /**
     * {@code true} si la lista puede terminar sin RETURN ni salida abrupta (función “cae” al final).
     */
    private boolean stmtListMayCompleteNormally(AstNode list) {
        if (list == null || list.getChildren().isEmpty()) {
            return true;
        }
        Completion c = Completion.MAY_CONTINUE;
        for (AstNode stmt : list.getChildren()) {
            c = c.seq(completionOf(stmt));
        }
        return c.mayFallThrough();
    }

    private enum Completion {
        ALWAYS_STOPS,
        MAY_CONTINUE;

        Completion seq(Completion next) {
            if (this == ALWAYS_STOPS) {
                return ALWAYS_STOPS;
            }
            return next;
        }

        boolean mayFallThrough() {
            return this == MAY_CONTINUE;
        }
    }

    private Completion completionOf(AstNode stmt) {
        if (stmt == null) {
            return Completion.MAY_CONTINUE;
        }
        if (unconditionalAbruptExit(stmt)) {
            return Completion.ALWAYS_STOPS;
        }
        return switch (stmt.getNodeType()) {
            case "IF_STATEMENT" -> completionIf(stmt);
            case "CASE_STATEMENT" -> completionCase(stmt);
            case "BLOCK_STATEMENT" -> {
                AstNode inner = findChild(stmt, "STATEMENT_LIST");
                yield completionOfList(inner);
            }
            case "EXCEPTION_BLOCK" -> {
                if ("TRY_CATCH".equals(stmt.getValue())) {
                    AstNode tryS = findChild(stmt, "TRY_SECTION");
                    AstNode tryList = tryS != null ? findChild(tryS, "STATEMENT_LIST") : null;
                    yield completionOfList(tryList);
                }
                yield Completion.MAY_CONTINUE;
            }
            default -> Completion.MAY_CONTINUE;
        };
    }

    private Completion completionIf(AstNode ifStmt) {
        AstNode thenList = findChild(ifStmt, "STATEMENT_LIST");
        Completion thenC = completionOfList(thenList);

        List<Completion> branches = new ArrayList<>();
        branches.add(thenC);
        for (AstNode c : ifStmt.getChildren()) {
            if ("ELSIF_BRANCH".equals(c.getNodeType())) {
                AstNode sl = findChild(c, "STATEMENT_LIST");
                branches.add(completionOfList(sl));
            }
        }
        AstNode elseBlk = findChild(ifStmt, "ELSE_BLOCK");
        if (elseBlk != null) {
            AstNode elseList = findChild(elseBlk, "STATEMENT_LIST");
            branches.add(completionOfList(elseList));
            return mergeAllStop(branches);
        }
        return Completion.MAY_CONTINUE;
    }

    private Completion completionCase(AstNode cas) {
        List<Completion> branches = new ArrayList<>();
        boolean hasElse = false;
        for (AstNode ch : cas.getChildren()) {
            if ("CASE_BRANCH".equals(ch.getNodeType())) {
                AstNode sl = findChild(ch, "STATEMENT_LIST");
                branches.add(completionOfList(sl));
            } else if ("ELSE_BLOCK".equals(ch.getNodeType())) {
                hasElse = true;
                AstNode sl = findChild(ch, "STATEMENT_LIST");
                branches.add(completionOfList(sl));
            }
        }
        if (!hasElse) {
            return Completion.MAY_CONTINUE;
        }
        return mergeAllStop(branches);
    }

    private Completion mergeAllStop(List<Completion> branches) {
        if (branches.isEmpty()) {
            return Completion.MAY_CONTINUE;
        }
        for (Completion b : branches) {
            if (b == Completion.MAY_CONTINUE) {
                return Completion.MAY_CONTINUE;
            }
        }
        return Completion.ALWAYS_STOPS;
    }

    private Completion completionOfList(AstNode list) {
        if (list == null || list.getChildren().isEmpty()) {
            return Completion.MAY_CONTINUE;
        }
        Completion acc = Completion.MAY_CONTINUE;
        for (AstNode s : list.getChildren()) {
            acc = acc.seq(completionOf(s));
        }
        return acc;
    }

    // -------------------------------------------------------------------------
    // PROC-SEM-006 infinite WHILE
    // -------------------------------------------------------------------------

    private static boolean subtreeContainsStatementType(AstNode node, String stmtType) {
        if (node == null) {
            return false;
        }
        if (stmtType.equals(node.getNodeType())) {
            return true;
        }
        for (AstNode ch : node.getChildren()) {
            if (subtreeContainsStatementType(ch, stmtType)) {
                return true;
            }
        }
        return false;
    }

    private void checkWhileInfiniteLoops(AstNode node) {
        if (node == null) {
            return;
        }
        if ("WHILE_STATEMENT".equals(node.getNodeType())) {
            AstNode cond = findChild(node, "CONDITION");
            AstNode ex = cond != null ? findChild(cond, "EXPRESSION") : null;
            AstNode body = findChild(node, "STATEMENT_LIST");
            if (body != null && subtreeContainsStatementType(body, "LEAVE_STATEMENT")) {
                /* MySQL / SQL uses LEAVE to exit WHILE — handler-driven FETCH loops look “unchanged” otherwise */
            } else if (ex != null && ex.getValue() != null) {
                String cv = ex.getValue();
                Set<String> condIds = new HashSet<>(extractIds(cv));
                Set<String> written = new HashSet<>();
                collectAssignedVars(body, written);
                boolean taut = PROC_TAUTOLOGY.matcher(cv).find();
                boolean unchanged = !condIds.isEmpty() && condIds.stream().noneMatch(written::contains);
                if (taut || unchanged) {
                    findings.add(new SemanticError(
                            "PROC-SEM-006",
                            t("Infinite loop", "Loop infinito"),
                            t("The WHILE condition cannot become false (tautology or variables not updated in the body).",
                                    "La condición del WHILE no puede volverse falsa (tautología o variables no modificadas en el cuerpo)."),
                            SemanticError.Severity.ERROR));
                }
            }
        }
        for (AstNode ch : node.getChildren()) {
            checkWhileInfiniteLoops(ch);
        }
    }

    private static void collectAssignedVars(AstNode node, Set<String> out) {
        if (node == null) {
            return;
        }
        if ("SET_STATEMENT".equals(node.getNodeType())
                || "ASSIGNMENT_STATEMENT".equals(node.getNodeType())) {
            AstNode t = findChild(node, "TARGET");
            if (t != null && t.getValue() != null) {
                out.add(canonical(t.getValue()));
            }
        }
        if ("FETCH_STATEMENT".equals(node.getNodeType())) {
            for (AstNode c : node.getChildren()) {
                if ("TARGET".equals(c.getNodeType()) && c.getValue() != null) {
                    out.add(canonical(c.getValue()));
                }
            }
        }
        for (AstNode ch : node.getChildren()) {
            collectAssignedVars(ch, out);
        }
    }

    // -------------------------------------------------------------------------
    // PROC-SEM-007 cursor open/close across branches
    // -------------------------------------------------------------------------

    private void checkCursorBranchLeak(AstNode rootIfSearch) {
        walkCheckCursorIf(rootIfSearch);
    }

    private void walkCheckCursorIf(AstNode n) {
        if (n == null) {
            return;
        }
        if ("IF_STATEMENT".equals(n.getNodeType())) {
            AstNode thenL = findChild(n, "STATEMENT_LIST");
            AstNode elseB = findChild(n, "ELSE_BLOCK");
            if (thenL != null && elseB != null) {
                AstNode elseL = findChild(elseB, "STATEMENT_LIST");
                if (elseL != null) {
                    asymmetricOpenClose(thenL, elseL);
                    asymmetricOpenClose(elseL, thenL);
                }
            }
        }
        for (AstNode ch : n.getChildren()) {
            walkCheckCursorIf(ch);
        }
    }

    private void asymmetricOpenClose(AstNode branchA, AstNode branchB) {
        Set<String> opensA = collectCursorOps(branchA, "OPEN_CURSOR_STATEMENT");
        Set<String> closesA = collectCursorOps(branchA, "CLOSE_CURSOR_STATEMENT");
        Set<String> closesB = collectCursorOps(branchB, "CLOSE_CURSOR_STATEMENT");
        for (String c : opensA) {
            if (closesB.contains(c) && !closesA.contains(c)) {
                findings.add(new SemanticError(
                        "PROC-SEM-007",
                        t("Possible cursor leak: OPEN in one branch and CLOSE in another",
                                "Posible leak de cursor: OPEN en una rama y CLOSE en otra"),
                        t("Move OPEN/CLOSE to the same scope or use TRY/FINALLY equivalent to guarantee CLOSE.",
                                "Mueva OPEN/CLOSE al mismo ámbito o use TRY/FINALLY equivalente para garantizar CLOSE."),
                        SemanticError.Severity.WARNING));
                return;
            }
        }
    }

    private static Set<String> collectCursorOps(AstNode node, String stmtType) {
        Set<String> out = new HashSet<>();
        if (stmtType.equals(node.getNodeType()) && node.getValue() != null) {
            out.add(canonical(node.getValue()));
        }
        for (AstNode ch : node.getChildren()) {
            out.addAll(collectCursorOps(ch, stmtType));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // PROC-SEM-008 self comparison
    // -------------------------------------------------------------------------

    private void walkSelfComparisons(AstNode n) {
        if (n == null) {
            return;
        }
        if ("CONDITION".equals(n.getNodeType())) {
            AstNode ex = findChild(n, "EXPRESSION");
            if (ex != null && ex.getValue() != null
                    && SELF_COMPARE.matcher(ex.getValue()).find()) {
                findings.add(new SemanticError(
                        "PROC-SEM-008",
                        t("Variable compared to itself", "Comparación de una variable consigo misma"),
                        t("Review the condition: you likely need another expression or literal.",
                                "Revise la condición: es probable que deba comparar con otra expresión o literal."),
                        SemanticError.Severity.WARNING));
            }
        }
        for (AstNode ch : n.getChildren()) {
            walkSelfComparisons(ch);
        }
    }

    // -------------------------------------------------------------------------
    // PROC-SEM-009 return type vs returned expression
    // -------------------------------------------------------------------------

    private void checkReturnTypeCompatibility(AstNode root) {
        if (!"CREATE_FUNCTION_STATEMENT".equals(root.getNodeType())) {
            return;
        }
        ReturnKind expected = inferExpectedReturnKind(root);
        if (expected == ReturnKind.UNKNOWN) {
            return;
        }
        for (AstNode ret : findAllDeep(root, "RETURN_STATEMENT")) {
            AstNode expr = findChild(ret, "EXPRESSION");
            if (expr == null || expr.getValue() == null || expr.getValue().isBlank()) {
                if (expected != ReturnKind.VOID_LIKE) {
                    findings.add(new SemanticError(
                            "PROC-SEM-009",
                            t("Return type incompatible with returned value",
                                    "Tipo de retorno incompatible con el valor devuelto"),
                            t("The function declares a non-void type but RETURN has no expression.",
                                    "La función declara un tipo no vacío pero RETURN no incluye expresión."),
                            SemanticError.Severity.ERROR));
                }
                continue;
            }
            ReturnKind actual = classifyExprReturn(expr.getValue());
            if (actual != ReturnKind.UNKNOWN && expected != actual && !compatible(expected, actual)) {
                findings.add(new SemanticError(
                        "PROC-SEM-009",
                        t("Return type incompatible with returned value",
                                "Tipo de retorno incompatible con el valor devuelto"),
                        t("Align the declared type with the RETURN expression or convert explicitly.",
                                "Alinee el tipo declarado con la expresión del RETURN o convierta explícitamente."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private enum ReturnKind { NUMERIC, STRING, BOOLEAN, VOID_LIKE, UNKNOWN }

    private ReturnKind inferExpectedReturnKind(AstNode createFn) {
        AstNode rt = findChild(createFn, "RETURN_TYPE");
        if (rt != null && rt.getValue() != null) {
            return classifySqlType(rt.getValue());
        }
        AstNode sig = findChild(createFn, "ROUTINE_SIGNATURE");
        if (sig != null && sig.getValue() != null) {
            Matcher m = RETURNS_TAIL.matcher(sig.getValue());
            if (m.find()) {
                return classifySqlType(m.group(1).trim());
            }
        }
        return ReturnKind.UNKNOWN;
    }

    private static ReturnKind classifySqlType(String text) {
        if (text == null) {
            return ReturnKind.UNKNOWN;
        }
        String u = text.toUpperCase(Locale.ROOT);
        if (u.contains("VOID") || u.contains(" TRIGGER")) {
            return ReturnKind.VOID_LIKE;
        }
        if (u.contains("CHAR") || u.contains("CLOB") || u.contains("TEXT")
                || u.contains("VARCHAR") || u.contains("NVARCHAR") || u.contains("STRING")) {
            return ReturnKind.STRING;
        }
        if (u.contains("BOOL")) {
            return ReturnKind.BOOLEAN;
        }
        if (u.contains("INT") || u.contains("NUMERIC") || u.contains("DECIMAL")
                || u.contains("FLOAT") || u.contains("DOUBLE") || u.contains("NUMBER")
                || u.contains("REAL") || u.contains("SMALLINT") || u.contains("BIGINT")) {
            return ReturnKind.NUMERIC;
        }
        return ReturnKind.UNKNOWN;
    }

    private static ReturnKind classifyExprReturn(String expr) {
        String s = expr.trim();
        if (STRING_LITERAL.matcher(s).matches()) {
            return ReturnKind.STRING;
        }
        if (NUMERIC_LITERAL.matcher(s).matches()) {
            return ReturnKind.NUMERIC;
        }
        if (Pattern.compile("^\\s*(TRUE|FALSE)\\s*$", Pattern.CASE_INSENSITIVE).matcher(s).matches()) {
            return ReturnKind.BOOLEAN;
        }
        return ReturnKind.UNKNOWN;
    }

    private static boolean compatible(ReturnKind expected, ReturnKind actual) {
        if (expected == ReturnKind.UNKNOWN || actual == ReturnKind.UNKNOWN) {
            return true;
        }
        return expected == actual;
    }

    // -------------------------------------------------------------------------
    // PROC-SEM-010 IN parameter assignment
    // -------------------------------------------------------------------------

    private void checkInParameterMutations(AstNode createRoot, AstNode block) {
        Set<String> inParams = RoutineParameterSupport.canonicalInOnlyNames(findChild(createRoot, "PARAMETER_LIST"));
        if (inParams.isEmpty()) {
            return;
        }
        walkAssignmentsTo(block, inParams);
    }

    private void walkAssignmentsTo(AstNode n, Set<String> inParams) {
        if (n == null) {
            return;
        }
        if ("SET_STATEMENT".equals(n.getNodeType()) || "ASSIGNMENT_STATEMENT".equals(n.getNodeType())) {
            AstNode t = findChild(n, "TARGET");
            if (t != null && t.getValue() != null) {
                String canon = canonical(t.getValue());
                if (inParams.contains(canon)) {
                    findings.add(new SemanticError(
                            "PROC-SEM-010",
                            t("IN parameter modified inside the body", "Parámetro IN modificado dentro del cuerpo"),
                            t("Use OUT/IN OUT, a local variable, or do not reassign the IN parameter.",
                                    "Use un parámetro OUT/IN OUT, una variable local o no reasigne el parámetro IN."),
                            SemanticError.Severity.WARNING));
                }
            }
        }
        for (AstNode ch : n.getChildren()) {
            walkAssignmentsTo(ch, inParams);
        }
    }

    // -------------------------------------------------------------------------
    // PROC-SEM-005 recursion
    // -------------------------------------------------------------------------

    private void maybeWarnRecursionWithoutBase() {
        if (rawSql == null) {
            return;
        }
        Matcher cm = CREATE_SUBPROGRAM_NAME.matcher(rawSql);
        if (!cm.find()) {
            return;
        }
        String name = cm.group(1);
        String body = rawSql.substring(cm.end());
        Pattern selfCall = Pattern.compile(
                "\\bCALL\\s+(?:[\\w.]+\\.)?" + Pattern.quote(name) + "\\b|\\bEXEC(?:UTE)?\\s+(?:[\\w.]+\\.)?"
                        + Pattern.quote(name) + "\\b|\\bSELECT\\s+" + Pattern.quote(name) + "\\s*\\(",
                Pattern.CASE_INSENSITIVE);
        Matcher sm = selfCall.matcher(body);
        if (!sm.find()) {
            return;
        }
        String before = body.substring(0, sm.start());
        if (!BEFORE_FIRST_SELF_CALL_HAS_IF.matcher(before).find()) {
            findings.add(new SemanticError(
                    "PROC-SEM-005",
                    t("Recursion without a detectable base case", "Recursión sin caso base detectable"),
                    t("Add an IF guard or another termination condition before self-invocation.",
                            "Añada una guarda IF u otra condición de terminación antes de la auto-invocación."),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static AstNode primaryExecutableBlock(AstNode root) {
        if ("BLOCK_STATEMENT".equals(root.getNodeType())) {
            return root;
        }
        if ("CREATE_PROCEDURE_STATEMENT".equals(root.getNodeType())
                || "CREATE_FUNCTION_STATEMENT".equals(root.getNodeType())
                || "CREATE_TRIGGER_STATEMENT".equals(root.getNodeType())) {
            return findChildDeep(root, "BLOCK_STATEMENT");
        }
        return null;
    }

    private static AstNode findChild(AstNode parent, String type) {
        for (AstNode c : parent.getChildren()) {
            if (type.equals(c.getNodeType())) {
                return c;
            }
        }
        return null;
    }

    private static AstNode findChildDeep(AstNode n, String type) {
        if (type.equals(n.getNodeType())) {
            return n;
        }
        for (AstNode c : n.getChildren()) {
            AstNode r = findChildDeep(c, type);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static List<AstNode> findAllDeep(AstNode n, String type) {
        List<AstNode> acc = new ArrayList<>();
        findAllDeepHelper(n, type, acc);
        return acc;
    }

    private static void findAllDeepHelper(AstNode n, String type, List<AstNode> acc) {
        if (type.equals(n.getNodeType())) {
            acc.add(n);
        }
        for (AstNode c : n.getChildren()) {
            findAllDeepHelper(c, type, acc);
        }
    }

    private static String canonical(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.startsWith("@")) {
            t = t.substring(1);
        }
        return t.toUpperCase(Locale.ROOT);
    }

    private static List<String> extractIds(String expr) {
        List<String> ids = new ArrayList<>();
        Matcher m = PROC_ID.matcher(expr);
        while (m.find()) {
            String id = canonical(m.group(1));
            if (!id.isEmpty() && !STOPWORDS.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static Set<String> buildStopwords() {
        String[] k = {
                "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "TRUE", "FALSE",
                "INTO", "VALUES", "INSERT", "UPDATE", "DELETE", "SET", "BEGIN", "END", "DECLARE",
                "CASE", "WHEN", "THEN", "ELSE", "IF", "LOOP", "WHILE", "FOR", "AS", "BY",
                "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "JOIN", "INNER", "LEFT", "RIGHT",
                "OUTER", "CROSS", "ON", "DISTINCT", "ALL", "UNION", "EXISTS", "IN", "BETWEEN",
                "LIKE", "IS", "COUNT", "SUM", "AVG", "MAX", "MIN", "DESC", "ASC",
                "OPEN", "FETCH", "CLOSE", "CURSOR", "FETCH_STATUS",
                "RETURN", "RETURNS", "CALL", "EXECUTE", "PERFORM",
                "PROCEDURE", "FUNCTION", "TRIGGER",
                "EXCEPTION", "RAISE", "THROW"
        };
        return Set.copyOf(Arrays.asList(k));
    }
}
