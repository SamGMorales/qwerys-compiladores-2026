package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Walks an {@link AstNode} tree produced by {@link SqlParser} and emits a list
 * of {@link SemanticError} findings covering correctness, safety, and style.
 *
 * <p>Dialect analyzers (MySQL, PostgreSQL, etc.) extend this class and override
 * {@link #analyzeDialectSpecific(List)} to add dialect-specific rules on top of
 * the generic checks performed by this base class.
 *
 * <p>Usage — parser-only:
 * <pre>
 *   SemanticAnalyzer sa = new SemanticAnalyzer(ast);
 *   List&lt;SemanticError&gt; findings = sa.analyze();
 * </pre>
 *
 * <p>Usage — with raw SQL for injection detection:
 * <pre>
 *   SemanticAnalyzer sa = new SemanticAnalyzer(ast, originalSql);
 *   List&lt;SemanticError&gt; findings = sa.analyze();
 * </pre>
 */
public class SemanticAnalyzer {

    // -------------------------------------------------------------------------
    // SQL-injection regex patterns
    // -------------------------------------------------------------------------

    /**
     * Quote-termination / stacked-query patterns — must be matched against the raw source (or each
     * string literal) because they depend on apostrophe boundaries.
     */
    private static final List<Pattern> INJECTION_STRING_PATTERNS = List.of(
            Pattern.compile("'\\s*OR\\s+'[^']*'\\s*=\\s*'[^']*'", Pattern.CASE_INSENSITIVE),
            Pattern.compile("'\\s*;\\s*(DROP|DELETE|TRUNCATE|ALTER)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("'\\s*--", Pattern.CASE_INSENSITIVE));

    /**
     * Token-sequence patterns evaluated after removing block comments only, so normal
     * documentation comments do not trigger a finding. Line comments are left intact so patterns
     * are not applied after naively stripping {@code --} (which could appear inside string literals).
     */
    private static final List<Pattern> INJECTION_STRUCTURAL_PATTERNS = List.of(
            Pattern.compile("\\b(OR|AND)\\s+\\d+\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bUNION\\s+(ALL\\s+)?SELECT\\b", Pattern.CASE_INSENSITIVE));

    // ── Patrones de contenido semántico ──────────────────────────────────────
    private static final java.util.regex.Pattern LIKE_ONLY_WILDCARDS =
            java.util.regex.Pattern.compile(
                    "LIKE\\s+'[%_\\s]+'", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern LIKE_SHORT_TERM =
            java.util.regex.Pattern.compile(
                    "LIKE\\s+'%(.{1,2})%?'\\s*(?:AND|OR|$|\\))",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern NULL_EQUALS =
            java.util.regex.Pattern.compile(
                    "(?:=|!=|<>)\\s*NULL\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern BETWEEN_INVERTED =
            java.util.regex.Pattern.compile(
                    "BETWEEN\\s+(\\d+)\\s+AND\\s+(\\d+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern DIVISION_BY_ZERO =
            java.util.regex.Pattern.compile(
                    "/\\s*0(?![\\d.])", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern EMPTY_STRING_FILTER =
            java.util.regex.Pattern.compile(
                    "(?:=|LIKE)\\s*''", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern CARTESIAN_PRODUCT =
            java.util.regex.Pattern.compile(
                    "FROM\\s+\\w+\\s*,\\s*\\w+", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern CROSS_TABLE_JOIN =
            java.util.regex.Pattern.compile(
                    "\\w+\\.\\w+\\s*=\\s*\\w+\\.\\w+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern TAUTOLOGY_WHERE =
            java.util.regex.Pattern.compile(
                    "\\bWHERE\\s+1\\s*=\\s*1\\b|\\bWHERE\\s+TRUE\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern IMPOSSIBLE_WHERE =
            java.util.regex.Pattern.compile(
                    "\\bWHERE\\s+1\\s*=\\s*0\\b|\\bWHERE\\s+0\\s*=\\s*1\\b|\\bWHERE\\s+FALSE\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern SELF_COMPARISON =
            java.util.regex.Pattern.compile(
                    "\\bWHERE\\b.*\\b(\\w{2,})\\s*=\\s*\\1\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    private static final java.util.regex.Pattern EMPTY_IN_LIST =
            java.util.regex.Pattern.compile(
                    "\\bIN\\s*\\(\\s*\\)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern SAME_COL_CONTRADICTION =
            java.util.regex.Pattern.compile(
                    "\\b(\\w+)\\s*=\\s*'([^']*)'[^;]*?\\bAND\\b[^;]*?\\b\\1\\s*=\\s*'([^']*)'",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    private static final java.util.regex.Pattern CONTRADICTORY_RANGE =
            java.util.regex.Pattern.compile(
                    "\\b(\\w+)\\s*>\\s*(\\d+)[^;]*?\\bAND\\b[^;]*?\\b\\1\\s*<\\s*(\\d+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    private static final java.util.regex.Pattern DANGEROUS_DELETE_UPDATE =
            java.util.regex.Pattern.compile(
                    "\\b(DELETE|UPDATE)\\b.*\\bWHERE\\s+1\\s*=\\s*1\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    // Detects INSERT INTO table VALUES (...) with no explicit column list
    private static final java.util.regex.Pattern INSERT_NO_COLS =
            java.util.regex.Pattern.compile(
                    "\\bINSERT\\s+INTO\\s+\\w+\\s+VALUES\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final Pattern PROC_TAUTOLOGY_COND =
            Pattern.compile("\\b1\\s*=\\s*1\\b|\\bTRUE\\b", Pattern.CASE_INSENSITIVE);

    /** {@code CREATE PROCEDURE/FUNCTION name} for PROC-015 heuristics. */
    private static final Pattern CREATE_PROC_NAME =
            Pattern.compile(
                    "(?is)\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION)\\s+(?:[\"\\w]+\\.|[\"\\w]+\\.)?[\"']?([A-Za-z_][\\w]*)[\"']?",
                    Pattern.CASE_INSENSITIVE);

    /** DML/exec patterns suggesting the procedure merits explicit exception handling. */
    private static final Pattern PROC_CRITICAL_BODY_OPS =
            Pattern.compile(
                    "\\b(INSERT\\s+INTO|UPDATE\\s+|DELETE\\s+FROM|MERGE\\s+INTO|TRUNCATE\\s+|EXEC(?:UTE)?\\s+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PROC_SELECT_INTO_BODY =
            Pattern.compile("\\bSELECT\\s+[\\s\\S]+?\\bINTO\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern OTHERS_IN_EXCEPTION_COND =
            Pattern.compile("\\bOTHERS\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern HANDLER_SILENT_PRINT =
            Pattern.compile(
                    "\\b(DBMS_OUTPUT\\s*\\.\\s*PUT_LINE|\\bPRINT\\b|PUT_LINE\\b|CONTEXT\\s+INFO|\\bCALL\\s+DBMS_OUTPUT)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern RAW_PROPAGATING_KEYWORDS =
            Pattern.compile("\\b(THROW|RAISERROR|RAISE_APPLICATION_ERROR|SIGNAL|RESIGNAL)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern BEFORE_FIRST_SELF_CALL_HAS_IF =
            Pattern.compile("\\bIF\\b", Pattern.CASE_INSENSITIVE);

    /** Identifiers in expressions for procedural analysis; excludes common SQL reserved words. */
    private static final Pattern PROC_IDENTIFIER = Pattern.compile("\\b(@?[A-Za-z_][\\w]*)\\b");

    /** Identifiers on the right-hand side of {@code =} in DML (SET or WHERE values, not column/table names). */
    private static final Pattern DML_RHS_IDENT = Pattern.compile(
            "=\\s*([A-Za-z_][\\w]*)\\b(?!\\s*[=(])",
            Pattern.CASE_INSENSITIVE);

    /** Variables listed after INTO in {@code SELECT … INTO} or {@code FETCH … INTO}. */
    private static final Pattern DML_INTO_IDENTS = Pattern.compile(
            "\\bINTO\\s+([A-Za-z_][\\w]*(?:\\s*,\\s*[A-Za-z_][\\w]*)*)",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> PROCEDURAL_STOPWORDS = buildProceduralStopwords();

    private static Set<String> buildProceduralStopwords() {
        Set<String> s = new HashSet<>();
        String[] k = {
                "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "NULL", "TRUE", "FALSE",
                "INTO", "VALUES", "INSERT", "UPDATE", "DELETE", "SET", "BEGIN", "END", "DECLARE",
                "DEFAULT", "CASE", "WHEN", "THEN", "ELSE", "IF", "LOOP", "WHILE", "FOR", "AS", "BY",
                "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "JOIN", "INNER", "LEFT", "RIGHT",
                "OUTER", "CROSS", "ON", "DISTINCT", "ALL", "UNION", "EXISTS", "IN", "BETWEEN",
                "LIKE", "IS", "COUNT", "SUM", "AVG", "MAX", "MIN", "INT", "DESC", "ASC",
                "OPEN", "FETCH", "CLOSE", "CURSOR",
                "COMMIT", "ROLLBACK", "SAVEPOINT", "TRANSACTION", "ROLLBACK",
                "GRANT", "REVOKE", "START", "WITH", "RECURSIVE",
                "OVER", "PARTITION", "ROWS", "RANGE", "UNBOUNDED", "PRECEDING",
                "FOLLOWING", "CURRENT", "WINDOW", "LATERAL",
                "RETURN", "RETURNS", "CALL", "EXECUTE", "PERFORM",
                "PROCEDURE", "FUNCTION", "TRIGGER", "VIEW", "INDEX",
                "TABLE", "CREATE", "ALTER", "DROP", "REPLACE", "TRUNCATE",
                "PRIMARY", "FOREIGN", "KEY", "REFERENCES", "CONSTRAINT",
                "BEFORE", "AFTER", "INSTEAD", "ROW", "STATEMENT",
                "NO", "ACTION", "CASCADE", "RESTRICT",
                "NUMBER", "VARCHAR2", "DATE", "BOOLEAN", "INTEGER", "VARCHAR",
                "CHAR", "TEXT", "NUMERIC", "FLOAT", "DOUBLE", "DECIMAL",
                "TYPE", "ROWTYPE", "AUTHID", "DEFINER",
                "AUTONOMOUS_TRANSACTION", "PRAGMA",
                "BULK", "COLLECT", "FORALL", "LIMIT",
                "PACKAGE", "BODY", "LANGUAGE",
                "EXIT", "LEAVE", "ITERATE", "REPEAT", "NEXT", "RETURN", "NULL",
                "EXCEPTION", "OTHERS", "TRY", "CATCH", "HANDLER", "CONTINUE",
                "RAISE", "SIGNAL", "RESIGNAL", "SQLSTATE", "SQLWARNING", "SQLEXCEPTION", "ELSIF",
                "NOTFOUND", "FOUND", "ISOPEN", "ROWCOUNT", "FETCH_STATUS", "SQLCODE", "SQLERRM",
                "SYSDATE", "SYSTIMESTAMP", "ROWNUM", "ROWID", "DUAL",
                "NEXTVAL", "CURRVAL", "LEVEL", "PRIOR",
                "BULK_ROWCOUNT", "LIMIT"
        };
        for (String x : k) {
            s.add(x);
        }
        return Collections.unmodifiableSet(s);
    }

    // -------------------------------------------------------------------------
    // State — protected so dialect subclasses can read them directly
    // -------------------------------------------------------------------------

    /** Root AST node produced by the parser. */
    protected final AstNode root;

    /** Optional raw SQL string — enables more reliable injection detection. */
    protected final String rawSql;

    /** UI locale from the client ({@code es…} → Spanish messages). */
    protected final Locale uiLocale;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public SemanticAnalyzer(AstNode root) {
        this(root, null, Locale.ENGLISH);
    }

    public SemanticAnalyzer(AstNode root, String rawSql) {
        this(root, rawSql, Locale.ENGLISH);
    }

    /**
     * @param uiLocale BCP 47 locale from the UI; Spanish primary language tag → Spanish analyzer text
     */
    public SemanticAnalyzer(AstNode root, String rawSql, Locale uiLocale) {
        Objects.requireNonNull(root, "root AstNode must not be null");
        this.root = root;
        this.rawSql = rawSql;
        this.uiLocale = uiLocale != null ? uiLocale : Locale.ENGLISH;
    }

    /** Localizes a user-visible pair (English default, Spanish when UI is Spanish). */
    protected final String t(String en, String es) {
        return AnalysisMessages.t(uiLocale, en, es);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs all semantic checks (generic + dialect-specific) and returns an
     * unmodifiable list of findings. The list is empty when every check passes.
     */
    public List<SemanticError> analyze() {
        if ("CLIENT_DELIMITER_STATEMENT".equals(root.getNodeType())) {
            return Collections.emptyList();
        }
        List<SemanticError> errors = new ArrayList<>();

        switch (root.getNodeType()) {
            case "SELECT_STATEMENT"  -> analyzeSelect(root, errors);
            case "WITH_SELECT_STATEMENT" -> {
                AstNode mainSelect = findMainSelectUnderWith(root);
                if (mainSelect != null) {
                    analyzeSelect(mainSelect, errors);
                }
            }
            case "DELETE_STATEMENT"  -> analyzeDelete(root, errors);
            case "UPDATE_STATEMENT"  -> analyzeUpdate(root, errors);
            case "INSERT_STATEMENT"  -> analyzeInsert(root, errors);
            case "CREATE_VIEW_STATEMENT"       -> analyzeCreateView(root, errors);
            case "CREATE_INDEX_STATEMENT"      -> analyzeCreateIndex(root, errors);
            case "ALTER_TABLE_STATEMENT"       -> analyzeAlter(root, errors);
            case "DROP_STATEMENT"              -> analyzeDrop(root, errors);
            case "GRANT_STATEMENT"             -> analyzeGrant(root, errors);
            case "REVOKE_STATEMENT"            -> analyzeRevoke(root, errors);
            case "BEGIN_TRANSACTION_STATEMENT",
                 "START_TRANSACTION_STATEMENT",
                 "SET_TRANSACTION_STATEMENT"  -> analyzeTransactionIsolation(root, errors);
            case "BLOCK_STATEMENT"            -> analyzeBlock(root, errors);
            case "DO_STATEMENT" -> {
                AstNode innerBlock = findChild(root, "BLOCK_STATEMENT");
                if (innerBlock == null) {
                    innerBlock = findChildDeep(root, "BLOCK_STATEMENT");
                }
                if (innerBlock != null) {
                    analyzeBlock(innerBlock, errors);
                }
            }
            case "CREATE_PROCEDURE_STATEMENT",
                 "CREATE_FUNCTION_STATEMENT",
                 "CREATE_TRIGGER_STATEMENT"    -> analyzeOracleProgramUnit(root, errors);
            case "CREATE_PACKAGE_BODY_STATEMENT" -> analyzeOraclePackageBody(root, errors);
            case "CREATE_PACKAGE_STATEMENT"  -> { /* spec: declaraciones sin análisis de bloque */ }
            case "IF_STATEMENT",
                 "WHILE_STATEMENT",
                 "LOOP_STATEMENT",
                 "FOR_STATEMENT",
                 "CASE_STATEMENT"           -> analyzeStandaloneProcedural(root, errors);
        }

        checkSqlInjection(errors);

        checkContentSemantics(errors);

        // Template-method hook: dialect subclasses add their own rules here
        analyzeDialectSpecific(errors);

        return Collections.unmodifiableList(errors);
    }

    /**
     * Override in dialect subclasses to add dialect-specific semantic rules.
     * Called after all generic checks have been applied.
     *
     * @param errors mutable list — append {@link SemanticError} findings to it
     */
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        // No-op in the base class
    }

    /** Main query {@code SELECT} under a {@code WITH_SELECT_STATEMENT} (not CTE inner selects). */
    private AstNode findMainSelectUnderWith(AstNode withRoot) {
        AstNode last = null;
        for (AstNode c : withRoot.getChildren()) {
            if ("SELECT_STATEMENT".equals(c.getNodeType())) {
                last = c;
            }
        }
        return last;
    }

    // =========================================================================
    // SELECT checks
    // =========================================================================

    private void analyzeSelect(AstNode stmt, List<SemanticError> errors) {

        AstNode columnList  = findChild(stmt, "COLUMN_LIST");
        AstNode tableNode   = findTableNode(stmt);
        AstNode whereClause = findChild(stmt, "WHERE_CLAUSE");
        AstNode orderBy     = findChild(stmt, "ORDER_BY");
        AstNode limit       = findChild(stmt, "LIMIT");

        if (columnList == null || columnList.getChildren().isEmpty()) {
            errors.add(new SemanticError(
                    "SE001",
                    t("Missing column list after SELECT", "Falta la lista de columnas tras SELECT"),
                    t("Specify at least one column or use SELECT * to select all columns",
                            "Indique al menos una columna o use SELECT * para seleccionar todas"),
                    SemanticError.Severity.ERROR));
        }

        if (tableNode == null || isTableRefEmpty(tableNode)) {
            errors.add(new SemanticError(
                    "SE002",
                    t("Missing table name after FROM", "Falta el nombre de tabla tras FROM"),
                    t("Provide the name of the table you want to query",
                            "Indique la tabla que desea consultar"),
                    SemanticError.Severity.ERROR));
        }

        if (whereClause != null) checkIncompleteWhere(whereClause, errors);

        if (columnList != null && isSelectStar(columnList) && hasJoin(stmt)) {
            errors.add(new SemanticError(
                    "SE004",
                    t("Using SELECT * with JOIN may return unexpected columns",
                            "SELECT * con JOIN puede devolver columnas inesperadas"),
                    t("Explicitly list the columns you need to avoid ambiguous or duplicate column names across joined tables",
                            "Liste explícitamente las columnas para evitar nombres ambiguos o duplicados entre tablas unidas"),
                    SemanticError.Severity.WARNING));
        }

        if (columnList != null) checkDuplicateColumns(columnList, errors);

        if (orderBy != null && columnList != null && !isSelectStar(columnList)) {
            checkOrderByColumnsInSelect(orderBy, columnList, errors);
        }

        if (limit != null && orderBy == null) {
            errors.add(new SemanticError(
                    "SE010",
                    t("LIMIT without ORDER BY may return inconsistent results",
                            "LIMIT sin ORDER BY puede devolver resultados inconsistentes"),
                    t("Add an ORDER BY clause to guarantee deterministic pagination",
                            "Añada ORDER BY para paginación determinista"),
                    SemanticError.Severity.WARNING));
        }

    }

    // =========================================================================
    // DELETE checks
    // =========================================================================

    private void analyzeDelete(AstNode stmt, List<SemanticError> errors) {

        AstNode tableRef = findChild(stmt, "TABLE_REF");
        if (tableRef == null || isTableRefEmpty(tableRef)) {
            errors.add(new SemanticError(
                    "SE002",
                    t("Missing table name after FROM", "Falta el nombre de tabla tras FROM"),
                    t("Provide the name of the table you want to delete from",
                            "Indique la tabla de la que desea borrar"),
                    SemanticError.Severity.ERROR));
        }

        AstNode whereClause = findChild(stmt, "WHERE_CLAUSE");
        if (whereClause != null) checkIncompleteWhere(whereClause, errors);

        if (whereClause == null) {
            errors.add(new SemanticError(
                    "SEM-001",
                    t("DELETE without WHERE will delete ALL rows in the table",
                            "DELETE sin WHERE borrará TODAS las filas de la tabla"),
                    t("Add a WHERE clause to filter which records to delete",
                            "Añada WHERE para filtrar qué filas eliminar"),
                    SemanticError.Severity.WARNING));
        }
    }

    // =========================================================================
    // UPDATE checks
    // =========================================================================

    private void analyzeUpdate(AstNode stmt, List<SemanticError> errors) {

        AstNode whereClause = findChild(stmt, "WHERE_CLAUSE");
        if (whereClause != null) checkIncompleteWhere(whereClause, errors);

        if (whereClause == null) {
            errors.add(new SemanticError(
                    "SEM-002",
                    t("UPDATE without WHERE will modify ALL rows in the table",
                            "UPDATE sin WHERE modificará TODAS las filas de la tabla"),
                    t("Add a WHERE clause to filter which records to update",
                            "Añada WHERE para filtrar qué filas actualizar"),
                    SemanticError.Severity.WARNING));
        }
    }

    // =========================================================================
    // INSERT checks
    // =========================================================================

    private void analyzeInsert(AstNode stmt, List<SemanticError> errors) {
        // AST-based: check for explicit column list
        AstNode columnList = findChild(stmt, "COLUMN_LIST");
        boolean hasColumnList = columnList != null && !columnList.getChildren().isEmpty();

        if (!hasColumnList) {
            // Only emit if the raw-SQL pattern check hasn't already caught this
            boolean caughtByRaw = rawSql != null && INSERT_NO_COLS.matcher(rawSql).find();
            if (!caughtByRaw) {
                errors.add(new SemanticError(
                        "SE-INSERT-NO-COLS",
                        t("INSERT without explicit column list", "INSERT sin lista explícita de columnas"),
                        t("Specifying column names makes the INSERT resilient to schema changes "
                                + "(added, removed, or reordered columns). Use: INSERT INTO table (col1, col2) VALUES (val1, val2)",
                                "Nombrar columnas hace el INSERT robusto ante cambios de esquema. "
                                + "Use: INSERT INTO tabla (col1, col2) VALUES (val1, val2)"),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    // =========================================================================
    // Shared sub-checks
    // =========================================================================

    private void checkIncompleteWhere(AstNode whereClause, List<SemanticError> errors) {
        if (whereClause.getChildren().isEmpty()) return;
        AstNode condition = whereClause.getChildren().get(0);
        if (isBarePredicate(condition)) {
            errors.add(new SemanticError(
                    "SE003",
                    t("Incomplete WHERE condition", "Condición WHERE incompleta"),
                    t("The WHERE clause contains a bare value without a comparison operator. "
                            + "Use a complete predicate such as: column = value",
                            "Hay un valor suelto sin operador de comparación. "
                            + "Use un predicado completo, p.ej. columna = valor"),
                    SemanticError.Severity.WARNING));
        }
    }

    private void checkDuplicateColumns(AstNode columnList, List<SemanticError> errors) {
        Set<String> seen     = new HashSet<>();
        Set<String> reported = new HashSet<>();

        for (AstNode child : columnList.getChildren()) {
            String name = resolveColumnName(child);
            if (name == null) continue;
            String key = columnPart(name).toLowerCase();
            if (!seen.add(key) && reported.add(key)) {
                errors.add(new SemanticError(
                        "SE008",
                        t("Duplicate column in SELECT list: '" + name + "'",
                                "Columna duplicada en SELECT: '" + name + "'"),
                        t("Remove the duplicate column reference to avoid redundant data in the result set",
                                "Elimine la columna duplicada para no repetir datos"),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private void checkOrderByColumnsInSelect(AstNode orderBy, AstNode columnList,
                                             List<SemanticError> errors) {
        Set<String> selectCols = columnList.getChildren().stream()
                .map(this::resolveColumnName)
                .filter(Objects::nonNull)
                .map(n -> columnPart(n).toLowerCase())
                .collect(Collectors.toSet());

        for (AstNode orderExpr : orderBy.getChildren()) {
            AstNode colNode = orderExpr.getChildren().isEmpty()
                    ? orderExpr
                    : orderExpr.getChildren().get(0);
            String colName = resolveColumnName(colNode);
            if (colName == null) continue;
            String key = columnPart(colName).toLowerCase();
            if (!selectCols.contains(key)) {
                errors.add(new SemanticError(
                        "SE009",
                        t("ORDER BY column '" + colName + "' not in SELECT list",
                                "ORDER BY con columna '" + colName + "' que no está en SELECT"),
                        t("Add '" + colName + "' to the SELECT column list, or remove it from ORDER BY",
                                "Añada '" + colName + "' al SELECT o quítela de ORDER BY"),
                        SemanticError.Severity.INFO));
            }
        }
    }

    private void checkSqlInjection(List<SemanticError> errors) {
        if (rawSql != null) {
            for (Pattern pattern : INJECTION_STRING_PATTERNS) {
                if (pattern.matcher(rawSql).find()) {
                    addSqlInjectionFinding(errors);
                    return;
                }
            }
            String withoutBlockComments = rawSql.replaceAll("/\\*[\\s\\S]*?\\*/", "");
            for (Pattern pattern : INJECTION_STRUCTURAL_PATTERNS) {
                if (pattern.matcher(withoutBlockComments).find()) {
                    addSqlInjectionFinding(errors);
                    return;
                }
            }
            return;
        }

        List<String> targets = new ArrayList<>();
        collectLiterals(root, targets);
        for (String target : targets) {
            for (Pattern pattern : INJECTION_STRING_PATTERNS) {
                if (pattern.matcher(target).find()) {
                    addSqlInjectionFinding(errors);
                    return;
                }
            }
        }
    }

    private void addSqlInjectionFinding(List<SemanticError> errors) {
        errors.add(new SemanticError(
                "SE007",
                t("Possible SQL injection detected", "Posible inyección SQL detectada"),
                t("Use parameterised queries (PreparedStatement / @Query with bind params) "
                                + "instead of raw string concatenation",
                        "Use consultas parametrizadas (PreparedStatement / @Query con parámetros enlazados) "
                                + "en lugar de concatenar cadenas."),
                SemanticError.Severity.ERROR));
    }

    private void checkContentSemantics(List<SemanticError> errors) {
        if (rawSql == null) return;
        // Strip string literals and comments so structural patterns don't fire on data values
        String sql = stripLiteralsAndComments(rawSql);

        // ── LIKE patterns (need rawSql to inspect literal content) ───────────
        if (LIKE_ONLY_WILDCARDS.matcher(rawSql).find()) {
            errors.add(new SemanticError("SE-LIKE-ALL",
                    t("LIKE pattern is only wildcards — matches every row", "LIKE solo con comodines — coincide con todas las filas"),
                    t("Add a selective literal fragment or reconsider the filter.",
                            "Añada un fragmento literal selectivo o revise el filtro."),
                    SemanticError.Severity.WARNING));
        } else if (LIKE_SHORT_TERM.matcher(rawSql).find()) {
            errors.add(new SemanticError("SE-LIKE-SHORT",
                    t("Very short LIKE term — may be an ineffective or accidental pattern",
                            "Término LIKE muy corto — patrón poco selectivo o accidental"),
                    t("Prefer longer literals or anchored patterns for index-friendly searches.",
                            "Prefiera literales más largos o patrones anclados para búsquedas con índice."),
                    SemanticError.Severity.WARNING));
        }

        // ── NULL comparison ───────────────────────────────────────────────────
        if (NULL_EQUALS.matcher(sql).find()) {
            errors.add(new SemanticError("SE-NULL-EQUALS",
                    t("Comparing to NULL with = / != — use IS NULL / IS NOT NULL",
                            "Comparar NULL con = / != — use IS NULL / IS NOT NULL"),
                    t("SQL three-valued logic: column = NULL is unknown, not true/false.",
                            "Lógica trivalente: col = NULL no es verdadero/falso; use IS NULL."),
                    SemanticError.Severity.WARNING));
        }

        // ── BETWEEN issues ────────────────────────────────────────────────────
        java.util.regex.Matcher bm = BETWEEN_INVERTED.matcher(sql);
        while (bm.find()) {
            if (Integer.parseInt(bm.group(1)) > Integer.parseInt(bm.group(2))) {
                errors.add(new SemanticError("SE-BETWEEN-INV",
                        t("BETWEEN bounds reversed — lower bound is greater than upper bound",
                                "Límites de BETWEEN invertidos — el inferior es mayor que el superior"),
                        t("Swap the two bounds or fix the range so low <= high.",
                                "Intercambie los límites o corrija el rango para que bajo <= alto."),
                        SemanticError.Severity.WARNING));
                break;
            }
        }

        // ── Runtime risks ─────────────────────────────────────────────────────
        if (DIVISION_BY_ZERO.matcher(sql).find()) {
            errors.add(new SemanticError("SE-DIV-ZERO",
                    t("Possible division by zero", "Posible división entre cero"),
                    t("Guard the divisor with CASE or NULLIF before dividing.",
                            "Proteja el denominador con CASE o NULLIF antes de dividir."),
                    SemanticError.Severity.WARNING));
        }

        // ── Vacuous filters ───────────────────────────────────────────────────
        // rawSql needed: after stripping, every 'value' becomes '' and would be a false positive
        if (EMPTY_STRING_FILTER.matcher(rawSql).find()) {
            errors.add(new SemanticError("SE-EMPTY-STR",
                    t("Filter compares to empty string — often unintentional",
                            "Filtro comparado con cadena vacía — suele ser involuntario"),
                    t("Confirm empty string is intended or use IS NULL for missing values.",
                            "Confirme si busca '' o use IS NULL para ausencia de valor."),
                    SemanticError.Severity.WARNING));
        }

        if (EMPTY_IN_LIST.matcher(sql).find()) {
            errors.add(new SemanticError("SE-EMPTY-IN",
                    t("Empty IN () list — likely illegal or useless", "Lista IN () vacía — inválida o inútil"),
                    t("Provide at least one value inside IN (...).",
                            "Proporcione al menos un valor dentro de IN (...)."),
                    SemanticError.Severity.WARNING));
        }

        // ── Cartesian product (solo si no hay condición de JOIN entre tablas) ─────
        if (CARTESIAN_PRODUCT.matcher(sql).find()
                && !CROSS_TABLE_JOIN.matcher(rawSql).find()) {
            errors.add(new SemanticError("SE-CARTESIAN",
                    t("Possible Cartesian product (comma FROM without join condition)",
                            "Posible producto cartesiano (FROM con comas sin condición de unión)"),
                    t("Add join predicates or use explicit JOIN ... ON.",
                            "Añada predicados de unión o use JOIN ... ON explícito."),
                    SemanticError.Severity.WARNING));
        }

        // ── Tautologies & impossible conditions ───────────────────────────────
        if (TAUTOLOGY_WHERE.matcher(sql).find()) {
            errors.add(new SemanticError("SE-TAUTOLOGY",
                    t("WHERE is always true — filter has no effect", "WHERE siempre verdadero — el filtro no reduce filas"),
                    t("Remove redundant WHERE or replace with a meaningful predicate.",
                            "Elimine WHERE redundante o use un predicado con sentido."),
                    SemanticError.Severity.WARNING));
        }

        if (IMPOSSIBLE_WHERE.matcher(sql).find()) {
            errors.add(new SemanticError("SE-IMPOSSIBLE",
                    t("WHERE is always false — query returns no rows", "WHERE siempre falso — la consulta no devuelve filas"),
                    t("Fix the predicate or remove the dead branch.",
                            "Corrija el predicado o elimine la rama imposible."),
                    SemanticError.Severity.WARNING));
        }

        if (SELF_COMPARISON.matcher(sql).find()) {
            errors.add(new SemanticError("SE-SELF-CMP",
                    t("Column compared to itself in WHERE", "Columna comparada consigo misma en WHERE"),
                    t("Such predicates are always true (for non-null) — likely a typo.",
                            "Suele ser tautología si no es NULL — revise si fue un error tipográfico."),
                    SemanticError.Severity.WARNING));
        }

        // ── Contradictory conditions ──────────────────────────────────────────
        // rawSql needed: must compare the actual literal values captured from quotes
        java.util.regex.Matcher sm = SAME_COL_CONTRADICTION.matcher(rawSql);
        if (sm.find() && !sm.group(2).equals(sm.group(3))) {
            errors.add(new SemanticError("SE-CONTRADICTS",
                    t("Contradictory equality on the same column", "Igualdades contradictorias sobre la misma columna"),
                    t("Two different literals for one column cannot both hold.",
                            "Dos literales distintos para una columna no pueden cumplirse a la vez."),
                    SemanticError.Severity.WARNING));
        }

        java.util.regex.Matcher rm = CONTRADICTORY_RANGE.matcher(sql);
        if (rm.find()) {
            int gt = Integer.parseInt(rm.group(2));
            int lt = Integer.parseInt(rm.group(3));
            if (lt <= gt) {
                errors.add(new SemanticError("SE-RANGE-IMPOSSIBLE",
                        t("Numeric range contradiction — upper bound not above lower bound",
                                "Rango numérico imposible — el límite superior no es mayor que el inferior"),
                        t("Fix AND predicates so the interval can contain values.",
                                "Ajuste los predicados AND para que el intervalo sea posible."),
                        SemanticError.Severity.WARNING));
            }
        }

        // ── Dangerous scoped DELETE/UPDATE ────────────────────────────────────
        if (DANGEROUS_DELETE_UPDATE.matcher(sql).find()) {
            errors.add(new SemanticError("SE-DANGER-SCOPE",
                    t("DELETE/UPDATE with permissive WHERE — may affect many rows",
                            "DELETE/UPDATE con WHERE muy permisivo — puede afectar muchas filas"),
                    t("Tighten the WHERE clause and verify row scope before running.",
                            "Restrinja el WHERE y verifique el alcance antes de ejecutar."),
                    SemanticError.Severity.WARNING));
        }

        // ── INSERT without explicit column list ───────────────────────────────
        // Fires only when the parser did not produce an INSERT_STATEMENT node
        // (in which case analyzeInsert() already handled it via the AST path).
        if (INSERT_NO_COLS.matcher(rawSql).find()
                && !"INSERT_STATEMENT".equals(root.getNodeType())) {
            errors.add(new SemanticError("SE-INSERT-NO-COLS",
                    t("INSERT without explicit column list", "INSERT sin lista explícita de columnas"),
                    t("Name columns explicitly: INSERT INTO t (a,b) VALUES (...) for safer schema evolution.",
                            "Nombre columnas: INSERT INTO t (a,b) VALUES (...) ante cambios de esquema."),
                    SemanticError.Severity.WARNING));
        }
    }

    // =========================================================================
    // DDL checks — CREATE VIEW
    // =========================================================================

    /**
     * DDL-001 — Vista definida con SELECT *.
     * Una vista que selecciona todas las columnas es frágil: si la tabla base
     * agrega o elimina columnas, la vista puede devolver resultados inesperados
     * o romper el esquema almacenado.
     */
    private void analyzeCreateView(AstNode stmt, List<SemanticError> errors) {
        AstNode select = findChildDeep(stmt, "SELECT_STATEMENT");
        if (select == null) return;

        AstNode columnList = findChild(select, "COLUMN_LIST");
        if (columnList != null && isSelectStar(columnList)) {
            errors.add(new SemanticError(
                    "DDL-001",
                    t("View with SELECT * is fragile if the base table changes",
                            "Vista con SELECT * es frágil si cambia la tabla base"),
                    t("Explicitly list the columns the view should expose. "
                                    + "If columns are added or removed from the base table the view definition "
                                    + "may silently return wrong data or fail to refresh.",
                            "Liste explícitamente las columnas. Si la tabla base cambia columnas, "
                                    + "la vista puede devolver datos incorrectos o fallar al refrescar."),
                    SemanticError.Severity.WARNING));
        }
    }

    // =========================================================================
    // DDL checks — CREATE INDEX
    // =========================================================================

    /**
     * DDL-002 — Índice creado sin especificar ninguna columna.
     * Un CREATE INDEX ... ON table sin lista de columnas es inválido en todos
     * los dialectos SQL estándar.
     */
    private void analyzeCreateIndex(AstNode stmt, List<SemanticError> errors) {
        AstNode columnList = findChild(stmt, "COLUMN_LIST");
        boolean hasColumns = columnList != null && !columnList.getChildren().isEmpty();

        if (!hasColumns) {
            errors.add(new SemanticError(
                    "DDL-002",
                    t("CREATE INDEX does not specify any column", "CREATE INDEX no especifica ninguna columna"),
                    t("Provide at least one column in the index definition: "
                                    + "CREATE INDEX idx_name ON table_name (column_name).",
                            "Indique al menos una columna: "
                                    + "CREATE INDEX idx_name ON tabla (columna)."),
                    SemanticError.Severity.ERROR));
        }
    }

    // =========================================================================
    // DDL checks — ALTER TABLE
    // =========================================================================

    /**
     * DDL-003 — ALTER TABLE que renombra o elimina una columna sin saber si
     * está referenciada en vistas, stored procedures, o código de aplicación.
     */
    private void analyzeAlter(AstNode stmt, List<SemanticError> errors) {
        AstNode operation = findChild(stmt, "ALTER_OPERATION");
        if (operation == null) return;

        String opValue = operation.getValue();
        if (opValue == null) return;

        if (opValue.equals("DROP_COLUMN") || opValue.equals("RENAME_COLUMN")) {
            AstNode colNameNode = findChild(operation, "COLUMN_NAME");
            String colName = colNameNode != null ? colNameNode.getValue() : t("the column", "la columna");

            errors.add(new SemanticError(
                    "DDL-003",
                    t("ALTER TABLE " + opValue.replace('_', ' ').toLowerCase()
                                    + " '" + colName + "' — verify it is not referenced in views or application code",
                            "ALTER TABLE " + opValue.replace('_', ' ').toLowerCase()
                                    + " '" + colName + "' — compruebe que no esté referenciada en vistas o código"),
                    t("Dropping or renaming a column can silently break views, stored procedures, "
                                    + "triggers, and application queries that reference it by name. "
                                    + "Search your codebase and database objects before applying this change.",
                            "Eliminar o renombrar una columna puede romper vistas, procedimientos, "
                                    + "disparadores y consultas que la usen. Revise el código y objetos antes de aplicar el cambio."),
                    SemanticError.Severity.WARNING));
        }
    }

    // =========================================================================
    // DDL checks — DROP
    // =========================================================================

    /**
     * DDL-004 — DROP TABLE / VIEW / INDEX sin IF EXISTS.
     * DDL-005 — DROP DATABASE / SCHEMA en cualquier entorno (warning crítico).
     */
    private void analyzeDrop(AstNode stmt, List<SemanticError> errors) {
        AstNode objectTypeNode = findChild(stmt, "OBJECT_TYPE");
        if (objectTypeNode == null) return;

        String objectType = objectTypeNode.getValue() != null
                ? objectTypeNode.getValue().toUpperCase()
                : "";

        boolean hasIfExists = findChild(stmt, "IF_EXISTS") != null;

        if (objectType.equals("DATABASE") || objectType.equals("SCHEMA")) {
            errors.add(new SemanticError(
                    "DDL-005",
                    t("DROP " + objectType + " will permanently delete the entire database and all its objects",
                            "DROP " + objectType + " eliminará permanentemente la base y todos sus objetos"),
                    t("This operation is irreversible. Make sure you have a full backup and that "
                                    + "you are not running against a production environment. "
                                    + "Consider using IF EXISTS to avoid errors if the database does not exist.",
                            "Operación irreversible. Tenga copia de seguridad y confirme el entorno. "
                                    + "IF EXISTS evita error si el objeto no existe."),
                    SemanticError.Severity.WARNING));
            return;
        }

        if (!hasIfExists) {
            errors.add(new SemanticError(
                    "DDL-004",
                    t("DROP " + objectType + " without IF EXISTS will raise an error if the object does not exist",
                            "DROP " + objectType + " sin IF EXISTS fallará si el objeto no existe"),
                    t("Add IF EXISTS to make the statement idempotent: "
                                    + "DROP " + objectType + " IF EXISTS <name>.",
                            "Añada IF EXISTS para idempotencia: DROP " + objectType + " IF EXISTS <nombre>."),
                    SemanticError.Severity.WARNING));
        }
    }

    // =========================================================================
    // DCL / TCL checks
    // =========================================================================

    /** DCL-001 — GRANT ALL concede cualquier privilegio sobre el objeto. */
    private void analyzeGrant(AstNode stmt, List<SemanticError> errors) {
        for (AstNode p : stmt.getChildren()) {
            if (!"PRIVILEGE".equals(p.getNodeType())) continue;
            if ("ALL".equalsIgnoreCase(String.valueOf(p.getValue()))) {
                errors.add(new SemanticError(
                        "DCL-001",
                        t("GRANT ALL is dangerous: grants every privilege on the object",
                                "GRANT ALL es peligroso: otorga todos los permisos disponibles sobre el objeto"),
                        t("Grant only needed privileges (e.g. SELECT, INSERT). In production avoid GRANT ALL.",
                                "Conceda solo los privilegios necesarios (p. ej. SELECT, INSERT). "
                                        + "En producción, evite GRANT ALL y revise el principio de mínimo privilegio."),
                        SemanticError.Severity.WARNING));
                return;
            }
        }
    }

    /** DCL-002 — REVOKE sobre usuario root/postgres. */
    private void analyzeRevoke(AstNode stmt, List<SemanticError> errors) {
        AstNode revokee = findChild(stmt, "REVOKEE");
        if (revokee == null || revokee.getValue() == null) return;
        String name = revokee.getValue().trim().toLowerCase();
        if ("root".equals(name) || "postgres".equals(name)) {
            errors.add(new SemanticError(
                    "DCL-002",
                    t("REVOKE on user \"" + revokee.getValue() + "\" (typical administrator account)",
                            "REVOKE aplicado sobre el usuario \"" + revokee.getValue()
                                    + "\" (cuenta típica de administrador)"),
                    t("Revoking privileges on admin accounts may break automation or monitoring — verify targets.",
                            "Revocar privilegios sobre cuentas de administración puede afectar operaciones "
                                    + "automáticas o supervisión. Verifique cuentas destino."),
                    SemanticError.Severity.WARNING));
        }
    }

    /** TCL-004 — READ UNCOMMITTED (lecturas sucias). */
    private void analyzeTransactionIsolation(AstNode stmt, List<SemanticError> errors) {
        for (AstNode iso : findAllChildrenDeep(stmt, "ISOLATION_LEVEL")) {
            if ("READ_UNCOMMITTED".equalsIgnoreCase(String.valueOf(iso.getValue()))) {
                errors.add(new SemanticError(
                        "TCL-004",
                        t("READ UNCOMMITTED allows dirty reads of uncommitted data",
                                "READ UNCOMMITTED permite lecturas sucias sobre datos aún no confirmados"),
                        t("Use READ COMMITTED or a stricter level unless you deliberately need uncommitted reads.",
                                "Use READ COMMITTED o un nivel más estricto salvo que necesite deliberadamente "
                                        + "leer cambios sin confirmar."),
                        SemanticError.Severity.WARNING));
                return;
            }
        }
    }

    // =========================================================================
    // Procedural block (generic DECLARE / BEGIN…END)
    // =========================================================================

    private void analyzeOracleProgramUnit(AstNode stmt, List<SemanticError> errors) {
        AstNode block = findChildDeep(stmt, "BLOCK_STATEMENT");
        if (block != null) {
            analyzeBlock(block, errors, RoutineParameterSupport.canonicalFormalNames(findChild(stmt, "PARAMETER_LIST")));
        }
    }

    private void analyzeOraclePackageBody(AstNode stmt, List<SemanticError> errors) {
        AstNode block = findChild(stmt, "BLOCK_STATEMENT");
        if (block != null) {
            analyzeBlock(block, errors);
        }
    }

    /**
     * Single {@code IF}/{@code WHILE}/{@code LOOP}/… without outer {@code BEGIN…END} — runs
     * procedural control-flow rules but not DECLARE/variable lifecycle (PROC-001–005).
     */
    protected void analyzeStandaloneProcedural(AstNode stmt, List<SemanticError> errors) {
        Set<String> declared = Collections.emptySet();
        Set<String> declaredCursors = Collections.emptySet();
        Set<String> readRefs = new HashSet<>();
        switch (stmt.getNodeType()) {
            case "IF_STATEMENT" -> analyzeProcIf(stmt, declared, declaredCursors, readRefs, errors);
            case "WHILE_STATEMENT" -> analyzeProcWhile(stmt, declared, declaredCursors, readRefs, errors);
            case "LOOP_STATEMENT" -> analyzeProcLoop(stmt, declared, declaredCursors, readRefs, errors);
            case "FOR_STATEMENT" -> analyzeProcFor(stmt, declared, declaredCursors, readRefs, errors);
            case "CASE_STATEMENT" -> analyzeProcCase(stmt, declared, declaredCursors, readRefs, errors);
            default -> { }
        }
        maybeWarnSelfRecursionWithoutBase(errors);
    }

    protected void analyzeBlock(AstNode root, List<SemanticError> errors) {
        analyzeBlock(root, errors, Collections.emptySet());
    }

    protected void analyzeBlock(AstNode root, List<SemanticError> errors, Set<String> formalParameterCanons) {
        Set<String> declaredVars = new HashSet<>();
        declaredVars.addAll(formalParameterCanons);
        Set<String> declaredCursors = new HashSet<>();
        Set<String> readRefs = new HashSet<>();

        AstNode declareSec = findChild(root, "DECLARE_SECTION");
        if (declareSec != null) {
            for (AstNode decl : declareSec.getChildren()) {
                String nodeType = decl.getNodeType();
                if (decl.getValue() == null) {
                    continue;
                }
                String canon = canonicalProcVar(decl.getValue());
                if ("VARIABLE_DECLARATION".equals(nodeType)) {
                    if (declaredCursors.contains(canon)) {
                        errors.add(new SemanticError(
                                "PROC-003",
                                t("Variable \"" + decl.getValue() + "\" clashes with a cursor name in this scope",
                                        "Variable \"" + decl.getValue() + "\" choca con un cursor en este ámbito"),
                                t("Rename the variable or the cursor so each identifier is unique.",
                                        "Renombre la variable o el cursor para que los identificadores sean únicos."),
                                SemanticError.Severity.ERROR));
                    } else if (!declaredVars.add(canon)) {
                        errors.add(new SemanticError(
                                "PROC-003",
                                t("Variable \"" + decl.getValue() + "\" is declared more than once in the same scope",
                                        "Variable \"" + decl.getValue() + "\" declarada más de una vez en el mismo ámbito"),
                                t("Remove duplicate declarations or rename one of the variables.",
                                        "Elimine declaraciones duplicadas o renombre una variable."),
                                SemanticError.Severity.ERROR));
                    }
                } else if ("CURSOR_DECLARATION".equals(nodeType)) {
                    if (declaredVars.contains(canon)) {
                        errors.add(new SemanticError(
                                "PROC-003",
                                t("Cursor \"" + decl.getValue() + "\" clashes with a variable name in this scope",
                                        "Cursor \"" + decl.getValue() + "\" choca con una variable en este ámbito"),
                                t("Rename the cursor or the variable so each identifier is unique.",
                                        "Renombre el cursor o la variable para que sean únicos."),
                                SemanticError.Severity.ERROR));
                    } else if (!declaredCursors.add(canon)) {
                        errors.add(new SemanticError(
                                "PROC-003",
                                t("Cursor \"" + decl.getValue() + "\" is declared more than once in the same scope",
                                        "Cursor \"" + decl.getValue() + "\" declarado más de una vez en el mismo ámbito"),
                                t("Remove duplicate declarations or rename one of the cursors.",
                                        "Elimine duplicados o renombre uno de los cursores."),
                                SemanticError.Severity.ERROR));
                    }
                }
            }
            for (AstNode decl : declareSec.getChildren()) {
                if (!"VARIABLE_DECLARATION".equals(decl.getNodeType())) {
                    continue;
                }
                AstNode def = findChild(decl, "DEFAULT_VALUE");
                if (def == null) {
                    continue;
                }
                AstNode ex = findChild(def, "EXPRESSION");
                recordReadsFromExpr(ex, declaredVars, declaredCursors, readRefs, errors);
            }
        }

        AstNode stmtList = findChild(root, "STATEMENT_LIST");
        if (stmtList != null) {
            Map<String, CursorFlowSlot> cursorState = initialCursorFlowState(declaredCursors);
            analyzeCursorFlowStatementList(stmtList, cursorState, declaredCursors, 0, errors);
            finalizeCursorSemantics(cursorState, declaredCursors, errors);
            maybeReportSingleFetchCursorOutsideLoop(root, declaredCursors, errors);

            for (AstNode stmt : stmtList.getChildren()) {
                analyzeProceduralStatement(stmt, declaredVars, declaredCursors, readRefs, errors);
            }
            if (isEffectivelyEmptyStatementList(stmtList)) {
                errors.add(new SemanticError(
                        "PROC-005",
                        t("Empty procedural block (BEGIN … END with no executable statements)",
                                "Bloque procedural vacío (BEGIN … END sin sentencias ejecutables)"),
                        t("Remove the dead block or add the intended logic.",
                                "Elimine el bloque muerto o añada la lógica prevista."),
                        SemanticError.Severity.WARNING));
            }
        }

        maybeWarnSelfRecursionWithoutBase(errors);

        analyzeProceduralExceptions(root, errors);

        for (String d : declaredVars) {
            if (!readRefs.contains(d)) {
                errors.add(new SemanticError(
                        "PROC-001",
                        t("Variable is declared but never read: " + d,
                                "Variable declarada y nunca leída: " + d),
                        t("Remove the unused declaration or use the variable.",
                                "Elimine la declaración no usada o utilice la variable."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    /** Mutable cursor lifetime flags for procedural control-flow approximation. */
    private static final class CursorFlowSlot {
        boolean currentlyOpen;
        boolean everOpened;

        CursorFlowSlot copy() {
            CursorFlowSlot c = new CursorFlowSlot();
            c.currentlyOpen = currentlyOpen;
            c.everOpened = everOpened;
            return c;
        }
    }

    private static Map<String, CursorFlowSlot> initialCursorFlowState(Set<String> declaredCursors) {
        Map<String, CursorFlowSlot> m = new HashMap<>();
        for (String c : declaredCursors) {
            m.put(c, new CursorFlowSlot());
        }
        return m;
    }

    private static Map<String, CursorFlowSlot> copyCursorSlotMap(Map<String, CursorFlowSlot> src) {
        Map<String, CursorFlowSlot> m = new HashMap<>();
        for (Map.Entry<String, CursorFlowSlot> e : src.entrySet()) {
            m.put(e.getKey(), e.getValue().copy());
        }
        return m;
    }

    /** OR-merge slot maps from mutually exclusive branches (IF / CASE). */
    private static void mergeCursorSlotMapsOr(
            Map<String, CursorFlowSlot> dest,
            List<Map<String, CursorFlowSlot>> branchOutcomes) {
        if (branchOutcomes.isEmpty()) {
            return;
        }
        Set<String> keys = new HashSet<>(dest.keySet());
        for (Map<String, CursorFlowSlot> b : branchOutcomes) {
            keys.addAll(b.keySet());
        }
        for (String k : keys) {
            CursorFlowSlot merged = new CursorFlowSlot();
            for (Map<String, CursorFlowSlot> b : branchOutcomes) {
                CursorFlowSlot s = b.get(k);
                if (s != null) {
                    merged.currentlyOpen |= s.currentlyOpen;
                    merged.everOpened |= s.everOpened;
                }
            }
            dest.put(k, merged);
        }
    }

    private void finalizeCursorSemantics(
            Map<String, CursorFlowSlot> slots,
            Set<String> declaredCursors,
            List<SemanticError> errors) {
        for (String c : declaredCursors) {
            CursorFlowSlot s = slots.get(c);
            if (s == null || !s.everOpened) {
                errors.add(new SemanticError(
                        "PROC-020",
                        t("Cursor \"" + c + "\" declared but never opened with OPEN",
                                "Cursor \"" + c + "\" declarado pero nunca abierto con OPEN"),
                        t("Open the cursor with OPEN before use, or remove an unused declaration.",
                                "Abra el cursor con OPEN antes de usarlo o elimine la declaración si sobra."),
                        SemanticError.Severity.WARNING));
                continue;
            }
            if (s.currentlyOpen) {
                errors.add(new SemanticError(
                        "PROC-021",
                        t("Possible leak — cursor \"" + c + "\" is still open at end of block",
                                "Posible fuga — el cursor \"" + c + "\" sigue abierto al final del bloque"),
                        t("Add CLOSE before leaving the block or after the last FETCH.",
                                "Añada CLOSE antes de salir del bloque o tras el último FETCH."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private void maybeReportSingleFetchCursorOutsideLoop(
            AstNode root, Set<String> declaredCursors, List<SemanticError> errors) {
        for (String c : declaredCursors) {
            int[] tally = countFetchesForCursorInScope(root, c);
            if (tally[0] == 1 && tally[1] == 1) {
                errors.add(new SemanticError(
                        "PROC-024",
                        t("Single-row cursor — consider SELECT ... INTO instead",
                                "Cursor para una fila — considere SELECT INTO directo"),
                        t("If you only need one row, SELECT ... INTO from the cursor query is often simpler.",
                                "Si solo necesita una fila, suele bastar SELECT ... INTO desde el SELECT del cursor."),
                        SemanticError.Severity.INFO));
            }
        }
    }

    /** {@code tally[0]} = total FETCH; {@code tally[1]} = FETCH statements not nested under LOOP/WHILE/FOR. */
    private static int[] countFetchesForCursorInScope(AstNode node, String cursorCanon) {
        int[] acc = new int[2];
        countFetchesWalker(node, cursorCanon, false, acc);
        return acc;
    }

    private static void countFetchesWalker(
            AstNode node, String cursorCanon, boolean inLoop, int[] acc) {
        if ("FETCH_STATEMENT".equals(node.getNodeType())
                && node.getValue() != null
                && canonicalProcVar(node.getValue()).equals(cursorCanon)) {
            acc[0]++;
            if (!inLoop) {
                acc[1]++;
            }
        }
        boolean childInLoop = inLoop
                || "LOOP_STATEMENT".equals(node.getNodeType())
                || "WHILE_STATEMENT".equals(node.getNodeType())
                || "FOR_STATEMENT".equals(node.getNodeType());
        for (AstNode ch : node.getChildren()) {
            countFetchesWalker(ch, cursorCanon, childInLoop, acc);
        }
    }

    private void analyzeCursorFlowStatementList(
            AstNode stmtList,
            Map<String, CursorFlowSlot> slots,
            Set<String> declaredCursors,
            int loopDepth,
            List<SemanticError> errors) {
        if (stmtList == null) {
            return;
        }
        for (AstNode stmt : stmtList.getChildren()) {
            analyzeCursorFlowStatement(stmt, slots, declaredCursors, loopDepth, errors);
        }
    }

    private void analyzeCursorFlowStatement(
            AstNode stmt,
            Map<String, CursorFlowSlot> slots,
            Set<String> declaredCursors,
            int loopDepth,
            List<SemanticError> errors) {
        switch (stmt.getNodeType()) {
            case "OPEN_CURSOR_STATEMENT" -> handleOpenCursor(stmt.getValue(), slots);
            case "CLOSE_CURSOR_STATEMENT" -> handleCloseCursor(stmt.getValue(), slots);
            case "FETCH_STATEMENT" -> handleFetchCursor(stmt.getValue(), slots, errors);
            case "BLOCK_STATEMENT" -> {
                AstNode body = findChild(stmt, "STATEMENT_LIST");
                analyzeCursorFlowStatementList(body, slots, declaredCursors, loopDepth, errors);
            }
            case "IF_STATEMENT" -> analyzeCursorFlowIf(stmt, slots, declaredCursors, loopDepth, errors);
            case "CASE_STATEMENT" -> analyzeCursorFlowCase(stmt, slots, declaredCursors, loopDepth, errors);
            case "LOOP_STATEMENT" -> {
                AstNode body = findChild(stmt, "STATEMENT_LIST");
                analyzeCursorFlowStatementList(body, slots, declaredCursors, loopDepth + 1, errors);
            }
            case "WHILE_STATEMENT", "FOR_STATEMENT" -> {
                AstNode body = findChild(stmt, "STATEMENT_LIST");
                analyzeCursorFlowStatementList(body, slots, declaredCursors, loopDepth + 1, errors);
            }
            default -> {
                /* control nodes without STATEMENT_LIST are ignored for cursor ordering */
            }
        }
    }

    private static void handleOpenCursor(String cursorName, Map<String, CursorFlowSlot> slots) {
        if (cursorName == null) {
            return;
        }
        CursorFlowSlot slot = slots.computeIfAbsent(canonicalProcVar(cursorName), k -> new CursorFlowSlot());
        slot.everOpened = true;
        slot.currentlyOpen = true;
    }

    private static void handleCloseCursor(String cursorName, Map<String, CursorFlowSlot> slots) {
        if (cursorName == null) {
            return;
        }
        CursorFlowSlot slot = slots.computeIfAbsent(canonicalProcVar(cursorName), k -> new CursorFlowSlot());
        slot.currentlyOpen = false;
    }

    private void handleFetchCursor(
            String cursorName, Map<String, CursorFlowSlot> slots, List<SemanticError> errors) {
        if (cursorName == null) {
            return;
        }
        CursorFlowSlot slot = slots.computeIfAbsent(canonicalProcVar(cursorName), k -> new CursorFlowSlot());
        if (!slot.currentlyOpen) {
            if (slot.everOpened) {
                errors.add(new SemanticError(
                        "PROC-023",
                        t("FETCH after CLOSE on cursor \"" + cursorName + "\"",
                                "FETCH después de CLOSE en el cursor \"" + cursorName + "\""),
                        t("Re-open the cursor with OPEN before FETCH or remove the earlier CLOSE.",
                                "Vuelva a abrir el cursor con OPEN antes del FETCH o elimine el CLOSE previo."),
                        SemanticError.Severity.ERROR));
            } else {
                errors.add(new SemanticError(
                        "PROC-022",
                        t("FETCH without prior OPEN on cursor \"" + cursorName + "\"",
                                "FETCH sin OPEN previo en el cursor \"" + cursorName + "\""),
                        t("Use OPEN before the first FETCH.",
                                "Use OPEN antes del primer FETCH."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private void analyzeCursorFlowIf(
            AstNode ifStmt,
            Map<String, CursorFlowSlot> slots,
            Set<String> declaredCursors,
            int loopDepth,
            List<SemanticError> errors) {
        Map<String, CursorFlowSlot> entry = copyCursorSlotMap(slots);
        List<Map<String, CursorFlowSlot>> outcomes = new ArrayList<>();

        for (AstNode child : ifStmt.getChildren()) {
            if ("ELSIF_BRANCH".equals(child.getNodeType())) {
                AstNode elsifList = findChild(child, "STATEMENT_LIST");
                Map<String, CursorFlowSlot> br = copyCursorSlotMap(entry);
                analyzeCursorFlowStatementList(elsifList, br, declaredCursors, loopDepth, errors);
                outcomes.add(br);
            }
        }

        AstNode mainThen = findChild(ifStmt, "STATEMENT_LIST");
        Map<String, CursorFlowSlot> thenBranch = copyCursorSlotMap(entry);
        analyzeCursorFlowStatementList(mainThen, thenBranch, declaredCursors, loopDepth, errors);
        outcomes.add(thenBranch);

        AstNode elseBlk = findChild(ifStmt, "ELSE_BLOCK");
        if (elseBlk != null) {
            AstNode elseList = findChild(elseBlk, "STATEMENT_LIST");
            Map<String, CursorFlowSlot> elseBranch = copyCursorSlotMap(entry);
            analyzeCursorFlowStatementList(elseList, elseBranch, declaredCursors, loopDepth, errors);
            outcomes.add(elseBranch);
        } else {
            outcomes.add(copyCursorSlotMap(entry));
        }

        mergeCursorSlotMapsOr(slots, outcomes);
    }

    private void analyzeCursorFlowCase(
            AstNode cas,
            Map<String, CursorFlowSlot> slots,
            Set<String> declaredCursors,
            int loopDepth,
            List<SemanticError> errors) {
        Map<String, CursorFlowSlot> entry = copyCursorSlotMap(slots);
        List<Map<String, CursorFlowSlot>> outcomes = new ArrayList<>();

        AstNode elseBlk = findChild(cas, "ELSE_BLOCK");

        for (AstNode child : cas.getChildren()) {
            if ("CASE_BRANCH".equals(child.getNodeType())) {
                AstNode sl = findChild(child, "STATEMENT_LIST");
                Map<String, CursorFlowSlot> br = copyCursorSlotMap(entry);
                analyzeCursorFlowStatementList(sl, br, declaredCursors, loopDepth, errors);
                outcomes.add(br);
            }
        }

        if (elseBlk != null) {
            AstNode elseList = findChild(elseBlk, "STATEMENT_LIST");
            Map<String, CursorFlowSlot> elseBranch = copyCursorSlotMap(entry);
            analyzeCursorFlowStatementList(elseList, elseBranch, declaredCursors, loopDepth, errors);
            outcomes.add(elseBranch);
        } else {
            outcomes.add(copyCursorSlotMap(entry));
        }

        mergeCursorSlotMapsOr(slots, outcomes);
    }

    /** LHS/target appearances count toward PROC-001 without triggering PROC-002 (PROC-004 covers bad writes). */
    private static void recordAssignmentTargetAppearance(Set<String> readRefs, String canonVar) {
        if (canonVar != null && !canonVar.isEmpty()) {
            readRefs.add(canonVar);
        }
    }

    /**
     * Records procedural variable reads at the current lexical scope; emits PROC-002 when the name is not a
     * declared variable or cursor.
     */
    private void recordReadsCheckingDeclarations(
            List<String> ids,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        for (String id : ids) {
            if (id == null || id.isEmpty() || PROCEDURAL_STOPWORDS.contains(id)) {
                continue;
            }
            readRefs.add(id);
            if (!declared.contains(id) && !declaredCursors.contains(id)) {
                errors.add(new SemanticError(
                        "PROC-002",
                        t("Variable \"" + id + "\" is used but not declared in this block",
                                "Variable \"" + id + "\" usada pero no declarada en este bloque"),
                        t("Declare the variable in the DECLARE section or correct the reference.",
                                "Declare la variable en DECLARE o corrija la referencia."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private void recordReadsFromExpr(
            AstNode expr,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        recordReadsCheckingDeclarations(
                extractProcReadsFromExpr(expr), declared, declaredCursors, readRefs, errors);
    }

    private void recordReadsFromDmlText(
            String dmlText,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        recordReadsCheckingDeclarations(
                extractDmlVariableReads(dmlText), declared, declaredCursors, readRefs, errors);
    }

    private void analyzeProceduralStatement(
            AstNode stmt,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        switch (stmt.getNodeType()) {
            case "SET_STATEMENT", "ASSIGNMENT_STATEMENT" -> {
                AstNode target = findChild(stmt, "TARGET");
                AstNode expr = findChild(stmt, "EXPRESSION");
                if (target != null && target.getValue() != null) {
                    String tv = canonicalProcVar(target.getValue());
                    recordAssignmentTargetAppearance(readRefs, tv);
                    if (!declared.contains(tv)) {
                        errors.add(new SemanticError(
                                "PROC-004",
                                t("Assignment to undeclared variable \"" + target.getValue() + "\"",
                                        "Asignación a variable no declarada \"" + target.getValue() + "\""),
                                t("Declare the variable first or correct the name.",
                                        "Declare primero la variable o corrija el nombre."),
                                SemanticError.Severity.ERROR));
                    }
                }
                recordReadsFromExpr(expr, declared, declaredCursors, readRefs, errors);
            }
            case "IF_STATEMENT" -> analyzeProcIf(stmt, declared, declaredCursors, readRefs, errors);
            case "WHILE_STATEMENT" -> analyzeProcWhile(stmt, declared, declaredCursors, readRefs, errors);
            case "FOR_STATEMENT" -> analyzeProcFor(stmt, declared, declaredCursors, readRefs, errors);
            case "LOOP_STATEMENT" -> analyzeProcLoop(stmt, declared, declaredCursors, readRefs, errors);
            case "CASE_STATEMENT" -> analyzeProcCase(stmt, declared, declaredCursors, readRefs, errors);
            case "EXIT_WHEN_STATEMENT" -> {
                AstNode cond = findChild(stmt, "CONDITION");
                AstNode ex = findChild(cond, "EXPRESSION");
                recordReadsFromExpr(ex, declared, declaredCursors, readRefs, errors);
            }
            case "FETCH_STATEMENT" -> {
                for (AstNode tgt : stmt.getChildren()) {
                    if (!"TARGET".equals(tgt.getNodeType()) || tgt.getValue() == null) {
                        continue;
                    }
                    String tv = canonicalProcVar(tgt.getValue());
                    recordAssignmentTargetAppearance(readRefs, tv);
                    if (!declared.contains(tv)) {
                        errors.add(new SemanticError(
                                "PROC-004",
                                t("FETCH INTO variable \"" + tgt.getValue() + "\" is not declared in this block",
                                        "Variable FETCH INTO \"" + tgt.getValue() + "\" no declarada en este bloque"),
                                t("Declare the variable in DECLARE or correct its name.",
                                        "Declare la variable en DECLARE o corrija el nombre."),
                                SemanticError.Severity.ERROR));
                    }
                }
            }
            case "OPEN_CURSOR_STATEMENT" -> {
                if (stmt.getValue() != null && !stmt.getValue().isBlank()) {
                    String cc = canonicalProcVar(stmt.getValue());
                    if (!cc.isEmpty() && !declaredCursors.contains(cc)) {
                        errors.add(new SemanticError(
                                "PROC-026",
                                t("Cursor \"" + stmt.getValue() + "\" is OPEN but not declared in this block",
                                        "Cursor \"" + stmt.getValue() + "\" abierto con OPEN pero no declarado en este bloque"),
                                t("Declare the cursor (DECLARE … CURSOR …) before OPEN or correct the name.",
                                        "Declare el cursor (DECLARE … CURSOR …) antes de OPEN o corrija el nombre."),
                                SemanticError.Severity.ERROR));
                    }
                }
            }
            case "CLOSE_CURSOR_STATEMENT" -> { }
            case "BLOCK_STATEMENT" -> {
                AstNode inner = findChild(stmt, "STATEMENT_LIST");
                analyzeStatementList(inner, declared, declaredCursors, readRefs, errors);
            }
            case "RAISE_STATEMENT" -> {
                AstNode exName = findChild(stmt, "EXCEPTION_NAME");
                if (exName != null && exName.getValue() != null) {
                    recordReadsFromExpr(
                            new AstNode("EXPRESSION", exName.getValue()),
                            declared,
                            declaredCursors,
                            readRefs,
                            errors);
                }
                AstNode appErr = findChild(stmt, "APPLICATION_ERROR_INVOCATION");
                if (appErr != null && appErr.getValue() != null) {
                    recordReadsFromExpr(
                            new AstNode("EXPRESSION", appErr.getValue()),
                            declared,
                            declaredCursors,
                            readRefs,
                            errors);
                }
            }
            case "RAW_STATEMENT" -> {
                AstNode ex = findChild(stmt, "EXPRESSION");
                if (ex != null && ex.getValue() != null) {
                    String txt = ex.getValue().stripLeading().toUpperCase(Locale.ROOT);
                    // INSERT omitted: "\bINTO\b" would treat table names after INSERT INTO as variables.
                    if (txt.startsWith("UPDATE") || txt.startsWith("DELETE")
                            || txt.startsWith("FETCH")
                            || (txt.startsWith("SELECT") && PROC_SELECT_INTO_BODY.matcher(ex.getValue()).find())) {
                        recordReadsFromDmlText(ex.getValue(), declared, declaredCursors, readRefs, errors);
                    } else {
                        recordReadsFromExpr(ex, declared, declaredCursors, readRefs, errors);
                    }
                }
            }
            default -> {
                // ignore unknown node shapes
            }
        }
    }

    private void analyzeStatementList(
            AstNode stmtList,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        if (stmtList == null) {
            return;
        }
        for (AstNode stmt : stmtList.getChildren()) {
            analyzeProceduralStatement(stmt, declared, declaredCursors, readRefs, errors);
        }
    }

    private void analyzeProcIf(
            AstNode ifStmt,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        for (AstNode child : ifStmt.getChildren()) {
            dispatchProcIfSubtree(child, declared, declaredCursors, readRefs, errors);
        }
    }

    private void dispatchProcIfSubtree(
            AstNode node,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        if ("CONDITION".equals(node.getNodeType())) {
            AstNode ex = findChild(node, "EXPRESSION");
            recordReadsFromExpr(ex, declared, declaredCursors, readRefs, errors);
            if (isProceduralTautologyCondition(ex)) {
                errors.add(new SemanticError(
                        "PROC-010",
                        t("IF condition is always true (tautology)",
                                "Condición del IF siempre verdadera (tautología)"),
                        t("Simplify control flow or confirm the condition is intentional.",
                                "Simplifique el flujo o confirme que la condición es intencional."),
                        SemanticError.Severity.WARNING));
            }
            return;
        }
        if ("STATEMENT_LIST".equals(node.getNodeType())) {
            analyzeStatementList(node, declared, declaredCursors, readRefs, errors);
            return;
        }
        if ("ELSIF_BRANCH".equals(node.getNodeType()) || "ELSE_BLOCK".equals(node.getNodeType())) {
            for (AstNode c : node.getChildren()) {
                dispatchProcIfSubtree(c, declared, declaredCursors, readRefs, errors);
            }
        }
    }

    private void analyzeProcWhile(
            AstNode wh,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        AstNode cond = findChild(wh, "CONDITION");
        AstNode ex = findChild(cond, "EXPRESSION");
        recordReadsFromExpr(ex, declared, declaredCursors, readRefs, errors);
        if (isProceduralTautologyCondition(ex)) {
            errors.add(new SemanticError(
                    "PROC-011",
                    t("Potential infinite loop", "Bucle infinito potencial"),
                    t("WHILE condition is always true — use a condition that can become false or redesign the loop.",
                            "La condición del WHILE es siempre verdadera; use una condición que pueda volverse falsa o "
                                    + "replantee el bucle."),
                    SemanticError.Severity.ERROR));
        }
        AstNode sl = findChild(wh, "STATEMENT_LIST");
        analyzeStatementList(sl, declared, declaredCursors, readRefs, errors);
    }

    private void analyzeProcFor(
            AstNode fo,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        AstNode it = findChild(fo, "ITERATOR");
        String iterCanon =
                (it != null && it.getValue() != null) ? canonicalProcVar(it.getValue()) : "";
        Set<String> loopDeclared = new HashSet<>(declared);
        if (!iterCanon.isEmpty()) {
            loopDeclared.add(iterCanon);
        }

        AstNode range = findChild(fo, "FOR_RANGE");
        if (range != null) {
            List<AstNode> bounds = range.getChildren();
            if (bounds.size() >= 2) {
                Integer lo = tryParsePlainIntLiteral(bounds.get(0));
                Integer hi = tryParsePlainIntLiteral(bounds.get(1));
                if (lo != null && hi != null && lo > hi) {
                    errors.add(new SemanticError(
                            "PROC-013",
                            t("FOR numeric range reversed (lower bound greater than upper)",
                                    "Rango numérico del FOR invertido (límite inferior mayor que el superior)"),
                            t("Swap the bounds or fix the iteration logic.",
                                    "Intercambie los límites o revise la lógica iterativa."),
                            SemanticError.Severity.WARNING));
                }
                recordReadsFromExpr(bounds.get(0), declared, declaredCursors, readRefs, errors);
                recordReadsFromExpr(bounds.get(1), declared, declaredCursors, readRefs, errors);
            }
        }
        // FOR_CURSOR: driver query is SQL — do not treat table/column identifiers as undeclared procedural vars.
        analyzeStatementList(findChild(fo, "STATEMENT_LIST"), loopDeclared, declaredCursors, readRefs, errors);
    }

    private void analyzeProcLoop(
            AstNode lp,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        if (!loopBodyHasExitWhenIgnoringNestedLoops(lp)) {
            errors.add(new SemanticError(
                    "PROC-012",
                    t("Loop has no exit condition", "Bucle sin condición de salida"),
                    t("Add EXIT WHEN (or your engine's equivalent) or rewrite with WHILE/FOR equivalent.",
                            "Añada EXIT WHEN (o el equivalente del motor) o reescriba con WHILE/FOR."),
                    SemanticError.Severity.ERROR));
        }
        analyzeStatementList(findChild(lp, "STATEMENT_LIST"), declared, declaredCursors, readRefs, errors);
    }

    private void analyzeProcCase(
            AstNode cas,
            Set<String> declared,
            Set<String> declaredCursors,
            Set<String> readRefs,
            List<SemanticError> errors) {
        if (findChild(cas, "ELSE_BLOCK") == null) {
            errors.add(new SemanticError(
                    "PROC-014",
                    t("Consider adding ELSE for uncovered cases", "Considere agregar ELSE para casos no contemplados"),
                    t("Explicit ELSE avoids implicit behavior when no branch matched.",
                            "Un ELSE explícito evita comportamiento implícito cuando ninguna rama coincidió."),
                    SemanticError.Severity.INFO));
        }
        for (AstNode ch : cas.getChildren()) {
            if ("CASE_BRANCH".equals(ch.getNodeType())) {
                for (AstNode p : ch.getChildren()) {
                    if ("CONDITION".equals(p.getNodeType())) {
                        AstNode ex = findChild(p, "EXPRESSION");
                        recordReadsFromExpr(ex, declared, declaredCursors, readRefs, errors);
                    } else if ("STATEMENT_LIST".equals(p.getNodeType())) {
                        analyzeStatementList(p, declared, declaredCursors, readRefs, errors);
                    }
                }
            } else if ("CASE_DISCRIMINANT".equals(ch.getNodeType())) {
                AstNode ex = findChild(ch, "EXPRESSION");
                recordReadsFromExpr(ex, declared, declaredCursors, readRefs, errors);
            } else if ("ELSE_BLOCK".equals(ch.getNodeType())) {
                for (AstNode p : ch.getChildren()) {
                    if ("STATEMENT_LIST".equals(p.getNodeType())) {
                        analyzeStatementList(p, declared, declaredCursors, readRefs, errors);
                    }
                }
            }
        }
    }

    private boolean isProceduralTautologyCondition(AstNode expr) {
        if (expr == null || expr.getValue() == null) {
            return false;
        }
        return PROC_TAUTOLOGY_COND.matcher(expr.getValue()).find();
    }

    private static Integer tryParsePlainIntLiteral(AstNode expressionNode) {
        if (expressionNode == null || expressionNode.getValue() == null) {
            return null;
        }
        String s = expressionNode.getValue().trim();
        if (s.matches("-?\\d+")) {
            return Integer.parseInt(s);
        }
        return null;
    }

    /**
     * True if this loop's body contains {@code EXIT WHILE} at the same loop nesting level,
     * ignoring exits that belong only to an inner {@code LOOP}.
     */
    private boolean loopBodyHasExitWhenIgnoringNestedLoops(AstNode loopStmt) {
        AstNode body = findChild(loopStmt, "STATEMENT_LIST");
        return body != null && stmtListHasExitWhenForEnclosingLoop(body);
    }

    private boolean stmtListHasExitWhenForEnclosingLoop(AstNode stmtList) {
        for (AstNode stmt : stmtList.getChildren()) {
            if ("EXIT_WHEN_STATEMENT".equals(stmt.getNodeType())
                    || "LEAVE_STATEMENT".equals(stmt.getNodeType())) {
                return true;
            }
            if ("LOOP_STATEMENT".equals(stmt.getNodeType())) {
                continue;
            }
            if ("IF_STATEMENT".equals(stmt.getNodeType()) && ifSubtreeHasExitWhen(stmt)) {
                return true;
            }
            AstNode inner = findChild(stmt, "STATEMENT_LIST");
            if (inner != null
                    && ("WHILE_STATEMENT".equals(stmt.getNodeType())
                    || "FOR_STATEMENT".equals(stmt.getNodeType()))) {
                if (stmtListHasExitWhenForEnclosingLoop(inner)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ifSubtreeHasExitWhen(AstNode ifStmt) {
        for (AstNode child : ifStmt.getChildren()) {
            if ("STATEMENT_LIST".equals(child.getNodeType())) {
                if (stmtListHasExitWhenForEnclosingLoop(child)) {
                    return true;
                }
            } else if ("ELSIF_BRANCH".equals(child.getNodeType()) || "ELSE_BLOCK".equals(child.getNodeType())) {
                for (AstNode c : child.getChildren()) {
                    if ("STATEMENT_LIST".equals(c.getNodeType()) && stmtListHasExitWhenForEnclosingLoop(c)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void maybeWarnSelfRecursionWithoutBase(List<SemanticError> errors) {
        if (rawSql == null) {
            return;
        }
        Matcher cm = CREATE_PROC_NAME.matcher(rawSql);
        if (!cm.find()) {
            return;
        }
        String name = cm.group(1);
        String body = rawSql.substring(cm.end());
        Pattern selfCall = Pattern.compile(
                "\\bCALL\\s+(?:[\\w.]+\\.)?" + Pattern.quote(name) + "\\b|\\bEXEC(?:UTE)?\\s+(?:[\\w.]+\\.)?"
                        + Pattern.quote(name) + "\\b",
                Pattern.CASE_INSENSITIVE);
        Matcher sm = selfCall.matcher(body);
        if (!sm.find()) {
            return;
        }
        String before = body.substring(0, sm.start());
        if (!BEFORE_FIRST_SELF_CALL_HAS_IF.matcher(before).find()) {
            errors.add(new SemanticError(
                    "PROC-015",
                    t("Apparent recursion without a clear base case before first self-call",
                            "Recursión aparente sin caso base claro antes de la primera auto-invocación"),
                    t("Add a termination condition (IF) or prefer an iterative solution.",
                            "Añada una condición de terminación (IF) o favorezca una solución iterativa."),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // Procedural — exception handling (PROC-030 … PROC-034)
    // -------------------------------------------------------------------------

    private void analyzeProceduralExceptions(AstNode root, List<SemanticError> errors) {
        maybeInformProc030MissingHandling(root, errors);

        for (AstNode eb : findAllChildrenDeep(root, "EXCEPTION_BLOCK")) {
            if ("TRY_CATCH".equals(eb.getValue())) {
                analyzeTryCatchSemantics(eb, errors);
            } else {
                analyzeWhenExceptionSemantics(eb, errors);
            }
        }

        proceduralBareRaiseWalker(root, false, errors);
    }

    private void maybeInformProc030MissingHandling(AstNode root, List<SemanticError> errors) {
        if (rawSql == null) {
            return;
        }
        if (!CREATE_PROC_NAME.matcher(rawSql).find()) {
            return;
        }
        boolean risky = PROC_CRITICAL_BODY_OPS.matcher(rawSql).find()
                || PROC_SELECT_INTO_BODY.matcher(rawSql).find();
        if (!risky) {
            return;
        }
        if (proceduralShowsExplicitHandling(root)) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-030",
                t("Consider handling exceptions in this stored procedure",
                        "Considere manejar excepciones en este procedimiento almacenado"),
                t("Add EXCEPTION/WHEN, TRY/CATCH (T-SQL), or DECLARE HANDLER (MySQL) as appropriate.",
                        "Añada un bloque EXCEPTION con WHEN, TRY/CATCH (T-SQL) o DECLARE HANDLER (MySQL) según el motor."),
                SemanticError.Severity.INFO));
    }

    private boolean proceduralShowsExplicitHandling(AstNode root) {
        return findChildDeep(root, "EXCEPTION_BLOCK") != null
                || findChildDeep(root, "HANDLER_DECLARATION") != null;
    }

    private void analyzeWhenExceptionSemantics(AstNode eb, List<SemanticError> errors) {
        Set<String> seenSelectors = new HashSet<>();
        for (AstNode h : eb.getChildren()) {
            if (!"EXCEPTION_HANDLER".equals(h.getNodeType())) {
                continue;
            }
            AstNode cond = findChild(h, "CONDITION");
            AstNode expr = cond != null ? findChild(cond, "EXPRESSION") : null;
            String rawCond = expr != null && expr.getValue() != null ? expr.getValue().trim() : "";

            reportDuplicateSelectors(seenSelectors, rawCond, errors);

            AstNode body = findChild(h, "STATEMENT_LIST");

            if (OTHERS_IN_EXCEPTION_COND.matcher(rawCond).find()
                    && !statementListEndsWithPropagatingRaise(body)) {
                errors.add(new SemanticError(
                        "PROC-031",
                        t("Catch-all handler without re-raise can hide bugs",
                                "Capturar todo sin re-lanzar oculta bugs"),
                        t("Pair WHEN OTHERS with RAISE, RAISE_APPLICATION_ERROR, or logging before swallowing.",
                                "Complete WHEN OTHERS con RAISE, RAISE_APPLICATION_ERROR o registro antes de tragarse la excepción."),
                        SemanticError.Severity.WARNING));
            }

            if (handlerSilencesException(body)) {
                errors.add(new SemanticError(
                        "PROC-033",
                        t("Exception may be silenced", "Excepción silenciada"),
                        t("Avoid only NULL/PRINT/no-op — log the cause and/or re-raise as needed.",
                                "Evite dejar sólo NULL, PRINT u omisión; registre la causa y/o vuelva a lanzar según necesite."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    private void analyzeTryCatchSemantics(AstNode eb, List<SemanticError> errors) {
        AstNode catchSec = findChild(eb, "CATCH_SECTION");
        AstNode body = catchSec != null ? findChild(catchSec, "STATEMENT_LIST") : null;

        if (!statementListEndsWithPropagatingRaise(body)) {
            errors.add(new SemanticError(
                    "PROC-031",
                    t("Catch-all handler without re-raise can hide bugs",
                            "Capturar todo sin re-lanzar oculta bugs"),
                    t("CATCH often needs THROW, RAISERROR, or explicit logging instead of returning silently.",
                            "En CATCH suele hacer falta THROW, RAISERROR o un registro explícito en lugar de devolver como si nada."),
                    SemanticError.Severity.WARNING));
        }
        if (handlerSilencesException(body)) {
            errors.add(new SemanticError(
                    "PROC-033",
                    t("Exception may be silenced", "Excepción silenciada"),
                    t("If the handler only prints or ignores the failure, review logging and propagation.",
                            "Si el bloque sólo imprime o ignora el fallo, revise el registro y la propagación antes de continuar."),
                    SemanticError.Severity.WARNING));
        }
    }

    private void reportDuplicateSelectors(Set<String> seenSelectors, String rawCond, List<SemanticError> errors) {
        if (rawCond.isBlank()) {
            return;
        }
        List<String> parts = Arrays.stream(rawCond.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        for (String p : parts) {
            String canon = canonExceptionSelector(p);
            if (canon.isEmpty()) {
                continue;
            }
            if (!seenSelectors.add(canon)) {
                errors.add(new SemanticError(
                        "PROC-034",
                        t("The same exception is handled more than once",
                                "La misma excepción está manejada más de una vez"),
                        t("Merge duplicate WHEN clauses or keep only one effective branch.",
                                "Unifique WHEN duplicados o deje sólo una rama efectiva."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private static String canonExceptionSelector(String fragment) {
        String t = fragment.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if ("OTHERS".equals(t)) {
            return "OTHERS";
        }
        return t;
    }

    private boolean statementListEndsWithPropagatingRaise(AstNode stmtList) {
        if (stmtList == null || stmtList.getChildren().isEmpty()) {
            return false;
        }
        AstNode last = null;
        List<AstNode> ch = stmtList.getChildren();
        for (int i = ch.size() - 1; i >= 0; i--) {
            AstNode s = ch.get(i);
            if (!isVacuousNoiseStatement(s)) {
                last = s;
                break;
            }
        }
        return last != null && statementPropagatesRaise(last);
    }

    private boolean isVacuousNoiseStatement(AstNode stmt) {
        if (!"RAW_STATEMENT".equals(stmt.getNodeType())) {
            return false;
        }
        AstNode ex = findChild(stmt, "EXPRESSION");
        String txt = ex != null ? ex.getValue() : null;
        return txt == null || txt.isBlank();
    }

    private boolean statementPropagatesRaise(AstNode stmt) {
        if ("RAISE_STATEMENT".equals(stmt.getNodeType())) {
            return true;
        }
        if ("RAW_STATEMENT".equals(stmt.getNodeType())) {
            AstNode ex = findChild(stmt, "EXPRESSION");
            if (ex == null || ex.getValue() == null) {
                return false;
            }
            return RAW_PROPAGATING_KEYWORDS.matcher(ex.getValue()).find();
        }
        return false;
    }

    private boolean handlerSilencesException(AstNode stmtList) {
        if (stmtList == null || stmtList.getChildren().isEmpty()) {
            return true;
        }
        boolean sawContent = false;
        for (AstNode stmt : stmtList.getChildren()) {
            if (isVacuousNoiseStatement(stmt)) {
                continue;
            }
            sawContent = true;
            if (!isSilentHandlingStatement(stmt)) {
                return false;
            }
        }
        return sawContent;
    }

    private boolean isSilentHandlingStatement(AstNode stmt) {
        if ("BLOCK_STATEMENT".equals(stmt.getNodeType())) {
            AstNode sl = findChild(stmt, "STATEMENT_LIST");
            return handlerSilencesException(sl);
        }
        if ("IF_STATEMENT".equals(stmt.getNodeType())
                || "CASE_STATEMENT".equals(stmt.getNodeType())
                || "LOOP_STATEMENT".equals(stmt.getNodeType())
                || "WHILE_STATEMENT".equals(stmt.getNodeType())
                || "FOR_STATEMENT".equals(stmt.getNodeType())) {
            return false;
        }
        if ("RAISE_STATEMENT".equals(stmt.getNodeType())) {
            return false;
        }
        if ("RAW_STATEMENT".equals(stmt.getNodeType())) {
            AstNode ex = findChild(stmt, "EXPRESSION");
            if (ex == null || ex.getValue() == null) {
                return true;
            }
            String t = ex.getValue().trim();
            if (t.equalsIgnoreCase("NULL")) {
                return true;
            }
            return HANDLER_SILENT_PRINT.matcher(t).find();
        }
        return false;
    }

    private void proceduralBareRaiseWalker(AstNode node, boolean inHandlerContext, List<SemanticError> errors) {
        if (node == null) {
            return;
        }
        String nt = node.getNodeType();
        if ("EXCEPTION_BLOCK".equals(nt) && "TRY_CATCH".equals(node.getValue())) {
            for (AstNode ch : node.getChildren()) {
                if ("TRY_SECTION".equals(ch.getNodeType())) {
                    proceduralBareRaiseWalker(ch, false, errors);
                } else if ("CATCH_SECTION".equals(ch.getNodeType())) {
                    proceduralBareRaiseWalker(ch, true, errors);
                } else {
                    proceduralBareRaiseWalker(ch, false, errors);
                }
            }
            return;
        }
        if ("EXCEPTION_HANDLER".equals(nt)) {
            for (AstNode ch : node.getChildren()) {
                if ("CONDITION".equals(ch.getNodeType())) {
                    continue;
                }
                proceduralBareRaiseWalker(ch, true, errors);
            }
            return;
        }
        if ("RAISE_STATEMENT".equals(nt)) {
            if (!inHandlerContext && isBareReraise(node)) {
                errors.add(new SemanticError(
                        "PROC-032",
                        t("RAISE without context (bare re-raise outside a handler)",
                                "RAISE sin contexto (re-lanzamiento implícito fuera del manejador)"),
                        t("Use a named exception, RAISE_APPLICATION_ERROR, or move inside WHEN/CATCH before an empty RAISE.",
                                "Use una excepción con nombre, RAISE_APPLICATION_ERROR o muévase dentro de WHEN/CATCH antes de RAISE 'vacío'."),
                        SemanticError.Severity.WARNING));
            }
            return;
        }

        for (AstNode ch : node.getChildren()) {
            proceduralBareRaiseWalker(ch, inHandlerContext, errors);
        }
    }

    private boolean isBareReraise(AstNode node) {
        boolean hasModifier = findChild(node, "RAISE_KIND") != null;
        boolean hasNamed = findChild(node, "EXCEPTION_NAME") != null;
        boolean hasApp = findChild(node, "APPLICATION_ERROR_INVOCATION") != null;
        boolean noNamedPayload = node.getValue() == null || node.getValue().isBlank();
        return !hasModifier && !hasNamed && !hasApp && noNamedPayload;
    }

    private boolean isEffectivelyEmptyStatementList(AstNode stmtList) {
        List<AstNode> ch = stmtList.getChildren();
        if (ch.isEmpty()) {
            return true;
        }
        for (AstNode s : ch) {
            if (!"RAW_STATEMENT".equals(s.getNodeType())) {
                return false;
            }
            AstNode ex = findChild(s, "EXPRESSION");
            if (ex != null && ex.getValue() != null && !ex.getValue().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private List<String> extractProcReadsFromExpr(AstNode expr) {
        List<String> out = new ArrayList<>();
        if (expr == null || expr.getValue() == null) {
            return out;
        }
        String cleanedValue = stripLiteralsAndComments(expr.getValue());
        Matcher m = PROC_IDENTIFIER.matcher(cleanedValue);
        while (m.find()) {
            String canon = canonicalProcVar(m.group());
            if (canon.isEmpty() || PROCEDURAL_STOPWORDS.contains(canon)) {
                continue;
            }
            out.add(canon);
        }
        return out;
    }

    /**
     * For UPDATE/DELETE/SELECT INTO/FETCH INTO embedded as procedural RAW_STATEMENT, collect procedural variables:
     * identifiers after {@code =} on the RHS (SET/WHERE values) and identifiers listed after INTO.
     * Table/column names and literals are not matched intentionally beyond stripping literals/comments first.
     */
    private List<String> extractDmlVariableReads(String dmlText) {
        if (dmlText == null || dmlText.isBlank()) {
            return new ArrayList<>();
        }
        String stripped = stripLiteralsAndComments(dmlText);
        List<String> out = new ArrayList<>();
        Matcher m1 = DML_RHS_IDENT.matcher(stripped);
        while (m1.find()) {
            String canon = canonicalProcVar(m1.group(1));
            if (!canon.isEmpty() && !PROCEDURAL_STOPWORDS.contains(canon)) {
                out.add(canon);
            }
        }
        Matcher m2 = DML_INTO_IDENTS.matcher(stripped);
        if (m2.find()) {
            for (String part : m2.group(1).split(",")) {
                String canon = canonicalProcVar(part.trim());
                if (!canon.isEmpty() && !PROCEDURAL_STOPWORDS.contains(canon)) {
                    out.add(canon);
                }
            }
        }
        return out;
    }

    private static String canonicalProcVar(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.startsWith("@")) {
            t = t.substring(1);
        }
        return t.toUpperCase(Locale.ROOT);
    }

    private String stripLiteralsAndComments(String sql) {
        return sql
                // T-SQL Unicode string literals N'text' — strip before plain quotes so `N` is not scraped as a variable
                .replaceAll("(?is)(?<![A-Za-z0-9_])N'(?:[^']|'')*'", "''")
                .replaceAll("'(?:[^'\\\\]|\\\\.)*'", "''") // 'content' → '' (keeps '' for EMPTY_STRING_FILTER on stripped)
                .replaceAll("--[^\n\r]*", "")               // remove -- line comments
                .replaceAll("/\\*[\\s\\S]*?\\*/", "");      // remove /* */ block comments
    }

    // =========================================================================
    // Tree helpers — protected so dialect subclasses can reuse them
    // =========================================================================

    /** Returns the first direct child of {@code parent} matching {@code nodeType}, or null. */
    protected AstNode findChild(AstNode parent, String nodeType) {
        for (AstNode child : parent.getChildren()) {
            if (nodeType.equals(child.getNodeType())) return child;
        }
        return null;
    }

    /**
     * Recursively finds the first node anywhere in the subtree whose type matches
     * {@code nodeType}, or null if not found.
     */
    protected AstNode findChildDeep(AstNode node, String nodeType) {
        if (nodeType.equals(node.getNodeType())) return node;
        for (AstNode child : node.getChildren()) {
            AstNode result = findChildDeep(child, nodeType);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * Collects all nodes in the subtree whose type matches {@code nodeType}.
     */
    protected List<AstNode> findAllChildrenDeep(AstNode node, String nodeType) {
        List<AstNode> result = new ArrayList<>();
        findAllDeepHelper(node, nodeType, result);
        return result;
    }

    private void findAllDeepHelper(AstNode node, String nodeType, List<AstNode> out) {
        if (nodeType.equals(node.getNodeType())) out.add(node);
        for (AstNode child : node.getChildren()) {
            findAllDeepHelper(child, nodeType, out);
        }
    }

    /** Finds the table node in a SELECT_STATEMENT (TABLE_REF or JOIN_EXPRESSION). */
    protected AstNode findTableNode(AstNode stmt) {
        for (AstNode child : stmt.getChildren()) {
            String t = child.getNodeType();
            if ("TABLE_REF".equals(t) || "JOIN_EXPRESSION".equals(t)) return child;
        }
        return null;
    }

    protected boolean isTableRefEmpty(AstNode node) {
        return "TABLE_REF".equals(node.getNodeType())
                && (node.getValue() == null || node.getValue().isBlank());
    }

    protected boolean isSelectStar(AstNode columnList) {
        List<AstNode> kids = columnList.getChildren();
        return kids.size() == 1
                && "COLUMN_REF".equals(kids.get(0).getNodeType())
                && "*".equals(kids.get(0).getValue());
    }

    protected boolean hasJoin(AstNode stmt) {
        return stmt.getChildren().stream()
                .anyMatch(c -> "JOIN_EXPRESSION".equals(c.getNodeType()));
    }

    protected boolean isBarePredicate(AstNode node) {
        return switch (node.getNodeType()) {
            case "COLUMN_REF", "LITERAL" -> true;
            default -> false;
        };
    }

    protected String resolveColumnName(AstNode node) {
        if (node == null) return null;
        return switch (node.getNodeType()) {
            case "COLUMN_REF"    -> node.getValue();
            case "LITERAL"       -> node.getValue();
            case "FUNCTION_CALL" -> {
                AstNode alias = findChild(node, "ALIAS");
                yield alias != null ? alias.getValue() : node.getValue() + "(...)";
            }
            case "ORDER_EXPR"    -> node.getChildren().isEmpty()
                    ? null
                    : resolveColumnName(node.getChildren().get(0));
            default -> null;
        };
    }

    protected String columnPart(String colName) {
        int dot = colName.lastIndexOf('.');
        return dot >= 0 ? colName.substring(dot + 1) : colName;
    }

    protected void collectLiterals(AstNode node, List<String> out) {
        if ("LITERAL".equals(node.getNodeType()) && node.getValue() != null) {
            out.add(node.getValue());
        }
        for (AstNode child : node.getChildren()) {
            collectLiterals(child, out);
        }
    }

    /**
     * Returns true if rawSql (case-insensitive) contains the given substring.
     * Safe to call even when rawSql is null.
     */
    protected boolean rawSqlContains(String token) {
        if (rawSql == null) return false;
        return rawSql.toUpperCase().contains(token.toUpperCase());
    }

    /**
     * Returns true if rawSql matches the given regex pattern.
     * Safe to call even when rawSql is null.
     */
    protected boolean rawSqlMatches(Pattern pattern) {
        if (rawSql == null) return false;
        return pattern.matcher(rawSql).find();
    }
}
