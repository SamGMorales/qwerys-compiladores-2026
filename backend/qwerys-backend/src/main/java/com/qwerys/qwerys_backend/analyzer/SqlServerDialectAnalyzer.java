package com.qwerys.qwerys_backend.analyzer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Semantic analyzer for SQL Server (T-SQL) specific rules.
 * Extends the generic {@link SemanticAnalyzer} with checks that are only
 * relevant when targeting a Microsoft SQL Server database.
 *
 * <p>Rule codes use the prefix {@code SS}.
 */
public class SqlServerDialectAnalyzer extends SemanticAnalyzer {

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    private static final Pattern NOLOCK_PATTERN =
            Pattern.compile("\\bNOLOCK\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SELECT_INTO_TEMP =
            Pattern.compile("\\bSELECT\\b.+?\\bINTO\\s+#\\w+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CURSOR_PATTERN =
            Pattern.compile("\\bCURSOR\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern OLD_STYLE_JOIN =
            Pattern.compile("\\bWHERE\\b(?:.(?!\\bJOIN\\b))*?\\w+\\.\\w+\\s*=\\s*\\w+\\.\\w+",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern IDENTITY_PATTERN =
            Pattern.compile("@@IDENTITY\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEPRECATED_TEXT_TYPES =
            Pattern.compile("\\b(\\btext\\b|\\bntext\\b|\\bimage\\b)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern MERGE_OUTPUT_PATTERN =
            Pattern.compile("\\bMERGE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern OUTPUT_CLAUSE_PATTERN =
            Pattern.compile("\\bOUTPUT\\b", Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public SqlServerDialectAnalyzer(AstNode root) {
        super(root, null, Locale.ENGLISH);
    }

    public SqlServerDialectAnalyzer(AstNode root, String rawSql) {
        super(root, rawSql, Locale.ENGLISH);
    }

    public SqlServerDialectAnalyzer(AstNode root, String rawSql, Locale uiLocale) {
        super(root, rawSql, uiLocale);
    }

    // -------------------------------------------------------------------------
    // Dialect-specific checks
    // -------------------------------------------------------------------------

    @Override
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        checkTopWithoutOrderBy(errors);
        checkNolockHint(errors);
        checkSelectIntoTemp(errors);
        checkCursor(errors);
        checkOldStyleJoin(errors);
        checkMergeWithoutOutput(errors);
        checkAtAtIdentity(errors);
        checkDeprecatedTextTypes(errors);
    }

    // -------------------------------------------------------------------------
    // SS001 — SELECT TOP without ORDER BY
    // -------------------------------------------------------------------------

    private void checkTopWithoutOrderBy(List<SemanticError> errors) {
        if (!"SELECT_STATEMENT".equals(root.getNodeType())) return;

        AstNode top     = findChild(root, "TOP");
        AstNode topPct  = findChild(root, "TOP_PERCENT");
        AstNode orderBy = findChild(root, "ORDER_BY");

        if ((top != null || topPct != null) && orderBy == null) {
            errors.add(new SemanticError(
                    "SS001",
                    t("SELECT TOP without ORDER BY returns arbitrary rows",
                            "SELECT TOP sin ORDER BY devuelve filas arbitrarias"),
                    t("Add an ORDER BY clause to make TOP return a deterministic result set: "
                            + "SELECT TOP 10 * FROM table ORDER BY created_date DESC",
                            "Añada ORDER BY para un resultado determinista: SELECT TOP 10 * FROM tabla ORDER BY fecha DESC"),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // SS002 — WITH (NOLOCK) dirty reads
    // -------------------------------------------------------------------------

    private void checkNolockHint(List<SemanticError> errors) {
        if (!rawSqlMatches(NOLOCK_PATTERN)) return;

        errors.add(new SemanticError(
                "SS002",
                t("WITH (NOLOCK) hint may return dirty (uncommitted) reads",
                        "WITH (NOLOCK) puede devolver lecturas sucias (no confirmadas)"),
                t("NOLOCK reads uncommitted data and can produce phantom rows or missing rows. "
                        + "Use READ COMMITTED SNAPSHOT ISOLATION (RCSI) instead: "
                        + "ALTER DATABASE db SET READ_COMMITTED_SNAPSHOT ON",
                        "NOLOCK lee datos no confirmados. Considere RCSI: "
                                + "ALTER DATABASE db SET READ_COMMITTED_SNAPSHOT ON"),
                SemanticError.Severity.WARNING));
    }

    // -------------------------------------------------------------------------
    // SS003 — SELECT INTO #temp table
    // -------------------------------------------------------------------------

    private void checkSelectIntoTemp(List<SemanticError> errors) {
        if (!rawSqlMatches(SELECT_INTO_TEMP)) return;

        errors.add(new SemanticError(
                "SS003",
                t("Temporary table created with SELECT INTO #temp",
                        "Tabla temporal creada con SELECT INTO #temp"),
                t("Remember to DROP TABLE #temp_table_name when done, or the table will persist "
                        + "for the duration of the connection. Consider using table variables (@var) "
                        + "for small result sets",
                        "Haga DROP de #temp al terminar o persistirá en la sesión. "
                                + "Para conjuntos pequeños considere variables de tabla (@var)"),
                SemanticError.Severity.INFO));
    }

    // -------------------------------------------------------------------------
    // SS004 — CURSOR detected
    // -------------------------------------------------------------------------

    private void checkCursor(List<SemanticError> errors) {
        if (!rawSqlMatches(CURSOR_PATTERN)) return;

        errors.add(new SemanticError(
                "SS004",
                t("CURSOR detected — cursors are significantly slower than set-based operations",
                        "CURSOR detectado — mucho más lento que operaciones por conjuntos"),
                t("Rewrite cursor logic using set-based SQL (UPDATE/INSERT/DELETE with JOINs, "
                        + "window functions, or CTEs). Cursors cause row-by-row processing "
                        + "and should only be used as a last resort",
                        "Reemplace por SQL por conjuntos (JOINs, ventanas, CTE). Los cursores procesan fila a fila"),
                SemanticError.Severity.WARNING));
    }

    // -------------------------------------------------------------------------
    // SS005 — Old-style join syntax in WHERE clause
    // -------------------------------------------------------------------------

    /**
     * Detects the T-SQL legacy join syntax where table columns are joined via
     * WHERE predicates instead of explicit JOIN ... ON syntax.
     */
    private void checkOldStyleJoin(List<SemanticError> errors) {
        if (!"SELECT_STATEMENT".equals(root.getNodeType())) return;

        // Only flag if there are multiple tables but no explicit JOINs
        boolean hasExplicitJoin = hasJoin(root);
        if (hasExplicitJoin) return;

        AstNode whereClause = findChild(root, "WHERE_CLAUSE");
        if (whereClause == null) return;

        // Count table.column = table.column predicates in WHERE
        if (rawSqlMatches(OLD_STYLE_JOIN)) {
            errors.add(new SemanticError(
                    "SS005",
                    t("Old-style implicit JOIN syntax detected in WHERE clause",
                            "JOIN implícito antiguo detectado en WHERE"),
                    t("Replace implicit join (WHERE t1.col = t2.col) with ANSI JOIN syntax: "
                            + "FROM table1 t1 INNER JOIN table2 t2 ON t1.col = t2.col",
                            "Sustituya JOIN implícito por FROM t1 INNER JOIN t2 ON t1.col = t2.col"),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // SS006 — MERGE without OUTPUT clause
    // -------------------------------------------------------------------------

    private void checkMergeWithoutOutput(List<SemanticError> errors) {
        if (!"MERGE_STATEMENT".equals(root.getNodeType())) return;

        AstNode output = findChild(root, "OUTPUT_CLAUSE");
        if (output == null) {
            errors.add(new SemanticError(
                    "SS006",
                    t("MERGE statement without OUTPUT clause", "MERGE sin cláusula OUTPUT"),
                    t("Consider adding an OUTPUT clause to capture inserted, updated, or deleted rows: "
                            + "OUTPUT inserted.id, deleted.old_value INTO @audit_table",
                            "Añada OUTPUT para capturar filas insertadas/actualizadas/eliminadas: "
                                    + "OUTPUT inserted.id, deleted.old_value INTO @audit_table"),
                    SemanticError.Severity.INFO));
        }
    }

    // -------------------------------------------------------------------------
    // SS007 — @@IDENTITY instead of SCOPE_IDENTITY()
    // -------------------------------------------------------------------------

    protected void checkAtAtIdentity(List<SemanticError> errors) {
        if (!rawSqlMatches(IDENTITY_PATTERN)) return;

        errors.add(new SemanticError(
                "SS007",
                t("@@IDENTITY may return IDs generated by triggers, not just the current statement",
                        "@@IDENTITY puede devolver IDs de triggers, no solo del statement actual"),
                t("Replace @@IDENTITY with SCOPE_IDENTITY() to get the ID from the current scope only: "
                        + "SELECT SCOPE_IDENTITY() AS NewId",
                        "Use SCOPE_IDENTITY() para el ámbito actual: SELECT SCOPE_IDENTITY() AS NewId"),
                SemanticError.Severity.WARNING));
    }

    // -------------------------------------------------------------------------
    // SS008 — Deprecated text / ntext / image types
    // -------------------------------------------------------------------------

    private void checkDeprecatedTextTypes(List<SemanticError> errors) {
        List<AstNode> createTables = findAllChildrenDeep(root, "CREATE_TABLE_STATEMENT");
        if ("CREATE_TABLE_STATEMENT".equals(root.getNodeType())) {
            createTables = new java.util.ArrayList<>(createTables);
            if (!createTables.contains(root)) createTables.add(0, root);
        }

        boolean foundInAst = false;
        String foundType = null;

        outer:
        for (AstNode createTable : createTables) {
            AstNode columnDefs = findChild(createTable, "COLUMN_DEFINITIONS");
            if (columnDefs == null) continue;
            for (AstNode colDef : columnDefs.getChildren()) {
                AstNode dataType = findChild(colDef, "DATA_TYPE");
                if (dataType == null) continue;
                String type = dataType.getValue();
                if (type == null) continue;
                String upper = type.toUpperCase();
                if (upper.equals("TEXT") || upper.equals("NTEXT") || upper.equals("IMAGE")) {
                    foundInAst = true;
                    foundType = type;
                    break outer;
                }
            }
        }

        // Also check raw SQL if AST didn't catch it (e.g. ALTER TABLE)
        if (!foundInAst && rawSqlMatches(DEPRECATED_TEXT_TYPES)) {
            foundInAst = true;
            foundType = "text/ntext/image";
        }

        if (foundInAst) {
            errors.add(new SemanticError(
                    "SS008",
                    t("Deprecated SQL Server data type: '" + foundType + "'",
                            "Tipo obsoleto de SQL Server: '" + foundType + "'"),
                    t("text, ntext, and image are deprecated since SQL Server 2005. "
                            + "Replace with: text → varchar(MAX), ntext → nvarchar(MAX), image → varbinary(MAX)",
                            "text, ntext e image están obsoletos. Sustituya por varchar(MAX), nvarchar(MAX), varbinary(MAX)"),
                    SemanticError.Severity.WARNING));
        }
    }
}
