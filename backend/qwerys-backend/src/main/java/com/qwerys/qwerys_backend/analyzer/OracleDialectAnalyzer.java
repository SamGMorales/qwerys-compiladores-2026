package com.qwerys.qwerys_backend.analyzer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Semantic analyzer for Oracle-specific rules.
 * Extends the generic {@link SemanticAnalyzer} with checks that are only
 * relevant when targeting an Oracle Database.
 *
 * <p>Rule codes use the prefix {@code ORA}.
 */
public class OracleDialectAnalyzer extends SemanticAnalyzer {

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    private static final Pattern ROWNUM_GT_WHERE =
            Pattern.compile("\\bWHERE\\b.+?\\bROWNUM\\s*>\\s*\\d+",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ROWNUM_PATTERN =
            Pattern.compile("\\bROWNUM\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern NVL_PATTERN =
            Pattern.compile("\\bNVL\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern DECODE_PATTERN =
            Pattern.compile("\\bDECODE\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern VARCHAR_PATTERN =
            Pattern.compile("\\bVARCHAR\\b(?!\\s*2)", Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_TYPE_PATTERN =
            Pattern.compile("\\bDATE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern CONNECT_BY_PATTERN =
            Pattern.compile("\\bCONNECT\\s+BY\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern START_WITH_PATTERN =
            Pattern.compile("\\bSTART\\s+WITH\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern IMPLICIT_CONVERSION =
            Pattern.compile(
                    "\\bWHERE\\b.+?\\b([A-Za-z_]\\w*\\.)?([A-Za-z_]\\w*)\\s*=\\s*\\d+",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CHAR_COLUMN_PATTERN =
            Pattern.compile("\\bCHAR\\b", Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public OracleDialectAnalyzer(AstNode root) {
        super(root, null, Locale.ENGLISH);
    }

    public OracleDialectAnalyzer(AstNode root, String rawSql) {
        super(root, rawSql, Locale.ENGLISH);
    }

    public OracleDialectAnalyzer(AstNode root, String rawSql, Locale uiLocale) {
        super(root, rawSql, uiLocale);
    }

    // -------------------------------------------------------------------------
    // Dialect-specific checks
    // -------------------------------------------------------------------------

    @Override
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        checkSelectWithoutDual(errors);
        checkRownumGtInWhere(errors);
        checkRownumWithoutOrderBy(errors);
        checkNvlVsCoalesce(errors);
        checkDecodeVsCase(errors);
        checkVarcharVsVarchar2(errors);
        checkOracleDateType(errors);
        checkConnectByWithoutStartWith(errors);
        checkImplicitTypeConversion(errors);
    }

    // -------------------------------------------------------------------------
    // ORA001 — SELECT with only literals but not FROM DUAL
    // -------------------------------------------------------------------------

    /**
     * In Oracle, selecting constant expressions requires FROM DUAL.
     * Detects SELECT statements where every column is a LITERAL (no column refs)
     * and the table is not DUAL.
     */
    private void checkSelectWithoutDual(List<SemanticError> errors) {
        if (!"SELECT_STATEMENT".equals(root.getNodeType())) return;

        AstNode tableNode = findTableNode(root);
        if (tableNode == null) return;

        // Determine table name (may be TABLE_REF directly or nested in JOIN_EXPRESSION)
        String tableName = null;
        if ("TABLE_REF".equals(tableNode.getNodeType())) {
            tableName = tableNode.getValue();
        } else if ("JOIN_EXPRESSION".equals(tableNode.getNodeType())) {
            // Has joins — skip this check
            return;
        }

        if ("DUAL".equalsIgnoreCase(tableName)) return;

        AstNode columnList = findChild(root, "COLUMN_LIST");
        if (columnList == null || columnList.getChildren().isEmpty()) return;

        boolean allLiterals = columnList.getChildren().stream()
                .allMatch(c -> "LITERAL".equals(c.getNodeType()));

        if (allLiterals) {
            errors.add(new SemanticError(
                    "ORA001",
                    t("Oracle requires FROM DUAL for SELECT without a real table",
                            "Oracle exige FROM DUAL para SELECT sin tabla real"),
                    t("When selecting constant expressions in Oracle, use the DUAL pseudo-table: "
                            + "SELECT 1+1 FROM DUAL  or  SELECT SYSDATE FROM DUAL",
                            "Para literales en Oracle use la pseudo-tabla DUAL: "
                                    + "SELECT 1+1 FROM DUAL o SELECT SYSDATE FROM DUAL"),
                    SemanticError.Severity.ERROR));
        }
    }

    // -------------------------------------------------------------------------
    // ORA002 — ROWNUM > n in WHERE (always returns 0 rows)
    // -------------------------------------------------------------------------

    private void checkRownumGtInWhere(List<SemanticError> errors) {
        if (!rawSqlMatches(ROWNUM_GT_WHERE)) return;

        errors.add(new SemanticError(
                "ORA002",
                t("ROWNUM > n in WHERE clause never returns rows",
                        "ROWNUM > n en WHERE nunca devuelve filas"),
                t("ROWNUM is assigned before ORDER BY so ROWNUM > n is always false for n >= 1. "
                        + "Wrap the query: SELECT * FROM (SELECT ROWNUM rn, t.* FROM table t) "
                        + "WHERE rn > n",
                        "ROWNUM se asigna antes de ORDER BY; para n >= 1 es falso. "
                                + "Envuelva: SELECT * FROM (SELECT ROWNUM rn, t.* FROM tabla t) WHERE rn > n"),
                SemanticError.Severity.WARNING));
    }

    // -------------------------------------------------------------------------
    // ORA003 — ROWNUM without ORDER BY (ordering not guaranteed)
    // -------------------------------------------------------------------------

    private void checkRownumWithoutOrderBy(List<SemanticError> errors) {
        if (!"SELECT_STATEMENT".equals(root.getNodeType())) return;

        AstNode orderBy = findChild(root, "ORDER_BY");
        if (orderBy != null) return;

        // Look for ROWNUM in WHERE or COLUMN_LIST via AST
        AstNode whereClause = findChild(root, "WHERE_CLAUSE");
        boolean rownumInWhere = whereClause != null
                && !findAllChildrenDeep(whereClause, "COLUMN_REF").stream()
                        .filter(n -> "ROWNUM".equalsIgnoreCase(n.getValue()))
                        .toList()
                        .isEmpty();

        AstNode colList = findChild(root, "COLUMN_LIST");
        boolean rownumInSelect = colList != null
                && colList.getChildren().stream()
                        .anyMatch(n -> "ROWNUM".equalsIgnoreCase(n.getValue()));

        if ((rownumInWhere || rownumInSelect || rawSqlMatches(ROWNUM_PATTERN))) {
            errors.add(new SemanticError(
                    "ORA003",
                    t("ROWNUM is assigned before ORDER BY — top-N results may be unpredictable",
                            "ROWNUM se asigna antes de ORDER BY — el top-N puede ser impredecible"),
                    t("To get ordered top-N rows, wrap the query: "
                            + "SELECT * FROM (SELECT * FROM table ORDER BY col) WHERE ROWNUM <= n. "
                            + "In Oracle 12c+ prefer: SELECT * FROM table ORDER BY col FETCH FIRST n ROWS ONLY",
                            "Para top-N ordenado, envuelva: "
                                    + "SELECT * FROM (SELECT * FROM tabla ORDER BY col) WHERE ROWNUM <= n. "
                                    + "En 12c+ preferible: ... ORDER BY col FETCH FIRST n ROWS ONLY"),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // ORA004 — NVL vs COALESCE (performance)
    // -------------------------------------------------------------------------

    private void checkNvlVsCoalesce(List<SemanticError> errors) {
        if (!rawSqlMatches(NVL_PATTERN)) return;

        errors.add(new SemanticError(
                "ORA004",
                t("NVL evaluates both arguments regardless of the first argument's value",
                        "NVL evalúa ambos argumentos aunque el primero no sea NULL"),
                t("NVL(a, b) always evaluates b even when a is not NULL. "
                        + "COALESCE short-circuits (stops at the first non-NULL value) and is more efficient "
                        + "when the second argument involves a subquery or expensive computation. "
                        + "Prefer COALESCE(a, b) over NVL(a, b) for performance",
                        "NVL(a, b) siempre evalúa b. COALESCE cortocircuita y rinde mejor con subconsultas costosas. "
                                + "Prefiera COALESCE(a, b) frente a NVL(a, b)"),
                SemanticError.Severity.INFO));
    }

    // -------------------------------------------------------------------------
    // ORA005 — DECODE vs CASE (portability)
    // -------------------------------------------------------------------------

    private void checkDecodeVsCase(List<SemanticError> errors) {
        if (!rawSqlMatches(DECODE_PATTERN)) return;

        errors.add(new SemanticError(
                "ORA005",
                t("DECODE is Oracle-specific and not portable to other databases",
                        "DECODE es propietario de Oracle y no es portable"),
                t("Replace DECODE(col, val1, res1, val2, res2, default) with standard SQL CASE: "
                        + "CASE col WHEN val1 THEN res1 WHEN val2 THEN res2 ELSE default END. "
                        + "CASE WHEN is more readable, supports range conditions, and is ANSI SQL",
                        "Sustituya DECODE por CASE estándar: "
                                + "CASE col WHEN val1 THEN res1 WHEN val2 THEN res2 ELSE default END. "
                                + "CASE WHEN es más legible y ANSI SQL"),
                SemanticError.Severity.INFO));
    }

    // -------------------------------------------------------------------------
    // ORA006 — VARCHAR vs VARCHAR2
    // -------------------------------------------------------------------------

    private void checkVarcharVsVarchar2(List<SemanticError> errors) {
        boolean foundInAst = false;

        List<AstNode> createTables = findAllChildrenDeep(root, "CREATE_TABLE_STATEMENT");
        if ("CREATE_TABLE_STATEMENT".equals(root.getNodeType())) {
            createTables = new java.util.ArrayList<>(createTables);
            if (!createTables.contains(root)) createTables.add(0, root);
        }

        outer:
        for (AstNode createTable : createTables) {
            AstNode columnDefs = findChild(createTable, "COLUMN_DEFINITIONS");
            if (columnDefs == null) continue;
            for (AstNode colDef : columnDefs.getChildren()) {
                AstNode dataType = findChild(colDef, "DATA_TYPE");
                if (dataType == null) continue;
                String type = dataType.getValue();
                if (type != null && VARCHAR_PATTERN.matcher(type).find()) {
                    foundInAst = true;
                    break outer;
                }
            }
        }

        if (!foundInAst && rawSqlMatches(VARCHAR_PATTERN)) {
            foundInAst = true;
        }

        if (foundInAst) {
            errors.add(new SemanticError(
                    "ORA006",
                    t("Use VARCHAR2 instead of VARCHAR in Oracle", "Use VARCHAR2 en lugar de VARCHAR en Oracle"),
                    t("In Oracle, VARCHAR is currently synonymous with VARCHAR2, but Oracle reserves "
                            + "the right to change VARCHAR semantics in future releases. "
                            + "Always use VARCHAR2 to guarantee consistent behaviour",
                            "En Oracle VARCHAR hoy equivale a VARCHAR2, pero Oracle puede cambiar la semántica. "
                                    + "Use VARCHAR2 para un comportamiento estable"),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // ORA007 — Oracle DATE includes time component
    // -------------------------------------------------------------------------

    private void checkOracleDateType(List<SemanticError> errors) {
        boolean found = false;

        List<AstNode> createTables = findAllChildrenDeep(root, "CREATE_TABLE_STATEMENT");
        if ("CREATE_TABLE_STATEMENT".equals(root.getNodeType())) {
            createTables = new java.util.ArrayList<>(createTables);
            if (!createTables.contains(root)) createTables.add(0, root);
        }

        outer:
        for (AstNode createTable : createTables) {
            AstNode columnDefs = findChild(createTable, "COLUMN_DEFINITIONS");
            if (columnDefs == null) continue;
            for (AstNode colDef : columnDefs.getChildren()) {
                AstNode dataType = findChild(colDef, "DATA_TYPE");
                if (dataType == null) continue;
                String type = dataType.getValue();
                if (type != null && "DATE".equalsIgnoreCase(type)) {
                    found = true;
                    break outer;
                }
            }
        }

        if (found) {
            errors.add(new SemanticError(
                    "ORA007",
                    t("Oracle DATE includes a time component (unlike ANSI SQL DATE)",
                            "DATE en Oracle incluye hora (a diferencia del DATE ANSI)"),
                    t("Oracle DATE stores both date and time (precision to seconds). "
                            + "For date-only comparisons use TRUNC(date_col) = TRUNC(SYSDATE). "
                            + "Use TIMESTAMP for sub-second precision",
                            "Oracle DATE guarda fecha y hora (segundos). Para solo fecha use TRUNC. "
                                    + "Use TIMESTAMP para subsegundos"),
                    SemanticError.Severity.INFO));
        }
    }

    // -------------------------------------------------------------------------
    // ORA008 — CONNECT BY without START WITH
    // -------------------------------------------------------------------------

    private void checkConnectByWithoutStartWith(List<SemanticError> errors) {
        if (!rawSqlMatches(CONNECT_BY_PATTERN)) return;

        if (!rawSqlMatches(START_WITH_PATTERN)) {
            errors.add(new SemanticError(
                    "ORA008",
                    t("CONNECT BY without START WITH starts traversal from ALL rows",
                            "CONNECT BY sin START WITH recorre TODAS las filas como raíz"),
                    t("Without START WITH, Oracle begins the hierarchical query from every row in the table, "
                            + "which can cause exponential performance degradation. "
                            + "Add: START WITH parent_id IS NULL (or your root condition)",
                            "Sin START WITH Oracle inicia desde cada fila, con coste exponencial. "
                                    + "Añada: START WITH parent_id IS NULL (o su condición raíz)"),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // ORA009 — Implicit type conversion (CHAR column compared to NUMBER)
    // -------------------------------------------------------------------------

    /**
     * Detects patterns like WHERE char_column = 123 which cause Oracle to
     * implicitly convert the column value, bypassing indexes.
     * This heuristic checks for CREATE TABLE CHAR columns compared against
     * numeric literals in a WHERE clause.
     */
    private void checkImplicitTypeConversion(List<SemanticError> errors) {
        boolean hasCharColumn = false;

        List<AstNode> createTables = findAllChildrenDeep(root, "CREATE_TABLE_STATEMENT");
        if ("CREATE_TABLE_STATEMENT".equals(root.getNodeType())) {
            createTables = new java.util.ArrayList<>(createTables);
            if (!createTables.contains(root)) createTables.add(0, root);
        }

        for (AstNode createTable : createTables) {
            AstNode columnDefs = findChild(createTable, "COLUMN_DEFINITIONS");
            if (columnDefs == null) continue;
            for (AstNode colDef : columnDefs.getChildren()) {
                AstNode dataType = findChild(colDef, "DATA_TYPE");
                if (dataType == null) continue;
                String type = dataType.getValue();
                if (type == null) continue;
                String upper = type.toUpperCase();
                if (upper.startsWith("CHAR") || upper.startsWith("VARCHAR")) {
                    hasCharColumn = true;
                    break;
                }
            }
            if (hasCharColumn) break;
        }

        // Also check raw SQL for VARCHAR/CHAR column definitions
        if (!hasCharColumn) {
            hasCharColumn = rawSqlMatches(CHAR_COLUMN_PATTERN);
        }

        if (hasCharColumn && rawSqlMatches(IMPLICIT_CONVERSION)) {
            errors.add(new SemanticError(
                    "ORA009",
                    t("Potential implicit type conversion between CHAR/VARCHAR2 and NUMBER",
                            "Posible conversión implícita CHAR/VARCHAR2 ↔ NUMBER"),
                    t("Comparing a character column to a numeric literal forces Oracle to convert "
                            + "every column value, which prevents index use and causes full table scans. "
                            + "Cast explicitly: WHERE TO_NUMBER(char_col) = 123  or store numbers as NUMBER type",
                            "Comparar columna carácter con literal numérico fuerza conversión y FTS. "
                                    + "Convierte explícitamente: WHERE TO_NUMBER(char_col) = 123 o use NUMBER"),
                    SemanticError.Severity.WARNING));
        }
    }
}
