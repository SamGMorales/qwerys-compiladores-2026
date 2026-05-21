package com.qwerys.qwerys_backend.analyzer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Semantic analyzer for PostgreSQL-specific rules.
 * Extends the generic {@link SemanticAnalyzer} with checks that are only
 * relevant when targeting a PostgreSQL database.
 *
 * <p>Rule codes use the prefix {@code PG}.
 */
public class PostgreSQLDialectAnalyzer extends SemanticAnalyzer {

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    private static final Pattern ILIKE_PATTERN =
            Pattern.compile("\\bILIKE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SERIAL_PATTERN =
            Pattern.compile("\\b(SERIAL|BIGSERIAL|SMALLSERIAL)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ARRAY_CONTAINS_OP =
            Pattern.compile("@>|<@|&&", Pattern.CASE_INSENSITIVE);

    private static final Pattern WITH_RECURSIVE_PATTERN =
            Pattern.compile("\\bWITH\\s+RECURSIVE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern UNION_ALL_PATTERN =
            Pattern.compile("\\bUNION\\s+ALL\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern LATERAL_PATTERN =
            Pattern.compile("\\bLATERAL\\b", Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public PostgreSQLDialectAnalyzer(AstNode root) {
        super(root, null, Locale.ENGLISH);
    }

    public PostgreSQLDialectAnalyzer(AstNode root, String rawSql) {
        super(root, rawSql, Locale.ENGLISH);
    }

    public PostgreSQLDialectAnalyzer(AstNode root, String rawSql, Locale uiLocale) {
        super(root, rawSql, uiLocale);
    }

    // -------------------------------------------------------------------------
    // Dialect-specific checks
    // -------------------------------------------------------------------------

    @Override
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        checkIlike(errors);
        checkReturningClause(errors);
        checkSerialDeprecated(errors);
        checkArrayOperators(errors);
        checkLateralJoin(errors);
        checkWithRecursive(errors);
    }

    // -------------------------------------------------------------------------
    // PG001 — ILIKE without pg_trgm index
    // -------------------------------------------------------------------------

    private void checkIlike(List<SemanticError> errors) {
        if (!rawSqlMatches(ILIKE_PATTERN)) return;

        errors.add(new SemanticError(
                "PG001",
                t("ILIKE is case-insensitive but may perform a full table scan",
                        "ILIKE ignora mayúsculas pero puede provocar escaneo completo"),
                t("ILIKE does not use standard B-tree indexes. For large tables, create a "
                        + "pg_trgm GIN or GiST index: CREATE INDEX ON table USING gin(column gin_trgm_ops)",
                        "ILIKE no usa índices B-tree estándar. En tablas grandes cree índice pg_trgm GIN/GiST: "
                                + "CREATE INDEX ON tabla USING gin(columna gin_trgm_ops)"),
                SemanticError.Severity.INFO));
    }

    // -------------------------------------------------------------------------
    // PG002 — RETURNING clause column validation
    // -------------------------------------------------------------------------

    /**
     * Validates that a RETURNING clause exists on INSERT/UPDATE/DELETE and that
     * it references at least one column (not empty).
     */
    private void checkReturningClause(List<SemanticError> errors) {
        String stmtType = root.getNodeType();
        boolean isDml = "INSERT_STATEMENT".equals(stmtType)
                || "UPDATE_STATEMENT".equals(stmtType)
                || "DELETE_STATEMENT".equals(stmtType);

        if (!isDml) return;

        AstNode returning = findChild(root, "RETURNING_CLAUSE");
        if (returning == null) return;

        if (returning.getChildren().isEmpty()) {
            errors.add(new SemanticError(
                    "PG002",
                    t("RETURNING clause is empty", "La cláusula RETURNING está vacía"),
                    t("Specify at least one column or * in the RETURNING clause: RETURNING id, updated_at",
                            "Especifique al menos una columna o * en RETURNING: RETURNING id, updated_at"),
                    SemanticError.Severity.ERROR));
        }
    }

    // -------------------------------------------------------------------------
    // PG003 — SERIAL / BIGSERIAL deprecated in PostgreSQL 14+
    // -------------------------------------------------------------------------

    private void checkSerialDeprecated(List<SemanticError> errors) {
        // Check both raw SQL and CREATE TABLE AST
        boolean foundInRaw = rawSqlMatches(SERIAL_PATTERN);

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
                if (type != null && SERIAL_PATTERN.matcher(type).find()) {
                    foundInAst = true;
                    break outer;
                }
            }
        }

        if (foundInRaw || foundInAst) {
            errors.add(new SemanticError(
                    "PG003",
                    t("SERIAL / BIGSERIAL are deprecated in PostgreSQL 14+",
                            "SERIAL / BIGSERIAL están obsoletos desde PostgreSQL 14+"),
                    t("Use identity columns instead: column_name INT GENERATED ALWAYS AS IDENTITY. "
                            + "Identity columns are standards-compliant and avoid sequence ownership issues",
                            "Use columnas IDENTITY: columna INT GENERATED ALWAYS AS IDENTITY. "
                                    + "Son estándar y evitan problemas de propiedad de secuencias"),
                    SemanticError.Severity.INFO));
        }
    }

    // -------------------------------------------------------------------------
    // PG004 — Array operators syntax validation
    // -------------------------------------------------------------------------

    /**
     * Detects PostgreSQL-specific array operators (@>, <@, &&) in raw SQL.
     * Verifies they appear in a WHERE-like context (between two operands).
     */
    private void checkArrayOperators(List<SemanticError> errors) {
        if (!rawSqlMatches(ARRAY_CONTAINS_OP)) return;

        // Malformed: operator at start/end of statement or doubled
        Pattern malformed = Pattern.compile(
                "(?:^|\\s)(?:@>|<@|&&)\\s*(?:@>|<@|&&)|(?:@>|<@|&&)\\s*(?:WHERE|FROM|;|$)",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

        if (rawSqlMatches(malformed)) {
            errors.add(new SemanticError(
                    "PG004",
                    t("Malformed PostgreSQL array operator usage", "Uso incorrecto de operadores de array de PostgreSQL"),
                    t("Array operators (@>, <@, &&) must appear between two array expressions: "
                            + "array_col @> ARRAY[1,2] or col && '{value}'::type[]",
                            "Los operadores (@>, <@, &&) deben ir entre dos expresiones de array: "
                                    + "array_col @> ARRAY[1,2] o col && '{valor}'::tipo[]"),
                    SemanticError.Severity.ERROR));
        }
    }

    // -------------------------------------------------------------------------
    // PG005 — LATERAL must reference a function or subquery
    // -------------------------------------------------------------------------

    private void checkLateralJoin(List<SemanticError> errors) {
        // Find LATERAL_JOIN or LATERAL_REF nodes in the AST
        List<AstNode> lateralNodes = findAllChildrenDeep(root, "LATERAL_JOIN");
        lateralNodes.addAll(findAllChildrenDeep(root, "LATERAL_REF"));

        for (AstNode lateral : lateralNodes) {
            // A valid LATERAL_REF has a SUBQUERY child or a table ref with function call
            AstNode subquery = findChild(lateral, "SUBQUERY");
            AstNode tableRef = findChild(lateral, "TABLE_REF");

            boolean valid = (subquery != null)
                    || (tableRef != null && tableRef.getValue() != null);

            if (!valid && lateral.getChildren().isEmpty()) {
                errors.add(new SemanticError(
                        "PG005",
                        t("LATERAL must reference a function or subquery",
                                "LATERAL debe referenciar una función o subconsulta"),
                        t("LATERAL can only be used with a subquery or a set-returning function: "
                                + "... JOIN LATERAL (SELECT ...) AS t ON true  or  "
                                + "... JOIN LATERAL func() AS t ON true",
                                "LATERAL solo con subconsulta o función que devuelve conjunto: "
                                        + "... JOIN LATERAL (SELECT ...) AS t ON true o ... JOIN LATERAL func() AS t ON true"),
                        SemanticError.Severity.ERROR));
            }
        }

        // Also check raw SQL for bare LATERAL without parenthesis or function
        if (rawSqlMatches(LATERAL_PATTERN)) {
            Pattern bareLateral = Pattern.compile(
                    "\\bLATERAL\\b\\s+(?![(\\w])", Pattern.CASE_INSENSITIVE);
            if (rawSqlMatches(bareLateral)) {
                errors.add(new SemanticError(
                        "PG005",
                        t("LATERAL must reference a function or subquery",
                                "LATERAL debe referenciar una función o subconsulta"),
                        t("LATERAL can only be used with a subquery (LATERAL (SELECT ...)) "
                                + "or a set-returning function (LATERAL generate_series(...))",
                                "LATERAL solo con subconsulta LATERAL (SELECT ...) o función generate_series(...)"),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    // -------------------------------------------------------------------------
    // PG006 — WITH RECURSIVE requires UNION ALL
    // -------------------------------------------------------------------------

    private void checkWithRecursive(List<SemanticError> errors) {
        if (!rawSqlMatches(WITH_RECURSIVE_PATTERN)) return;

        if (!rawSqlMatches(UNION_ALL_PATTERN)) {
            errors.add(new SemanticError(
                    "PG006",
                    t("Recursive CTE (WITH RECURSIVE) requires UNION ALL between base and recursive case",
                            "CTE recursiva (WITH RECURSIVE) requiere UNION ALL entre caso base y recursivo"),
                    t("Structure a recursive CTE as: WITH RECURSIVE cte AS ( "
                            + "<base case> UNION ALL <recursive case> ) SELECT ...",
                            "Estructure: WITH RECURSIVE cte AS ( <base> UNION ALL <recursivo> ) SELECT ..."),
                    SemanticError.Severity.ERROR));
        }
    }
}
