package com.qwerys.qwerys_backend.analyzer;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Semantic analyzer for MySQL-specific rules.
 * Extends the generic {@link SemanticAnalyzer} with checks that are only
 * relevant when targeting a MySQL database.
 *
 * <p>Rule codes use the prefix {@code MY}.
 */
public class MySQLDialectAnalyzer extends SemanticAnalyzer {

    // -------------------------------------------------------------------------
    // Patterns used for raw-SQL inspection
    // -------------------------------------------------------------------------

    private static final Pattern ON_DUPLICATE_KEY =
            Pattern.compile("\\bON\\s+DUPLICATE\\s+KEY\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEPRECATED_PASSWORD_FN =
            Pattern.compile("\\bPASSWORD\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern DEPRECATED_ENCRYPT_FN =
            Pattern.compile("\\bENCRYPT\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern JSON_TABLE_FN =
            Pattern.compile("\\bJSON_TABLE\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Set<String> AGGREGATE_FUNCTIONS =
            Set.of("COUNT", "SUM", "AVG", "MAX", "MIN");

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public MySQLDialectAnalyzer(AstNode root) {
        super(root, null, Locale.ENGLISH);
    }

    public MySQLDialectAnalyzer(AstNode root, String rawSql) {
        super(root, rawSql, Locale.ENGLISH);
    }

    public MySQLDialectAnalyzer(AstNode root, String rawSql, Locale uiLocale) {
        super(root, rawSql, uiLocale);
    }

    // -------------------------------------------------------------------------
    // Dialect-specific checks
    // -------------------------------------------------------------------------

    @Override
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        checkOnDuplicateKey(errors);
        checkGroupByNonAggregated(errors);
        checkLimitWithoutOrderBy(errors);
        checkDeprecatedFunctions(errors);
        checkJsonTable(errors);
        checkAutoIncrementOnNonPk(errors);
    }

    // -------------------------------------------------------------------------
    // MY001 — ON DUPLICATE KEY without guaranteed unique index
    // -------------------------------------------------------------------------

    private void checkOnDuplicateKey(List<SemanticError> errors) {
        if (!rawSqlMatches(ON_DUPLICATE_KEY)) return;

        errors.add(new SemanticError(
                "MY001",
                t("ON DUPLICATE KEY requires a UNIQUE index or PRIMARY KEY",
                        "ON DUPLICATE KEY requiere índice UNIQUE o PRIMARY KEY"),
                t("Ensure the target table has a UNIQUE index or PRIMARY KEY on the conflict column(s); "
                                + "otherwise this statement behaves as a plain INSERT",
                        "Asegure UNIQUE o PRIMARY KEY en la(s) columna(s) de conflicto; "
                                + "si no, el comportamiento es como INSERT simple."),
                SemanticError.Severity.WARNING));
    }

    // -------------------------------------------------------------------------
    // MY002 — GROUP BY with non-aggregated columns
    // -------------------------------------------------------------------------

    /**
     * In MySQL 5.x the server allows non-aggregated columns in the SELECT list
     * that are not part of the GROUP BY (ONLY_FULL_GROUP_BY mode may be off).
     * MySQL 8+ enables ONLY_FULL_GROUP_BY by default, so such queries fail.
     */
    private void checkGroupByNonAggregated(List<SemanticError> errors) {
        if (!"SELECT_STATEMENT".equals(root.getNodeType())) return;

        AstNode groupBy    = findChild(root, "GROUP_BY");
        AstNode columnList = findChild(root, "COLUMN_LIST");
        if (groupBy == null || columnList == null) return;

        // Collect GROUP BY column names (bare name only, lower-cased)
        Set<String> groupByCols = new java.util.HashSet<>();
        for (AstNode gc : groupBy.getChildren()) {
            String name = resolveColumnName(gc);
            if (name != null) groupByCols.add(columnPart(name).toLowerCase());
        }

        // Check SELECT columns
        for (AstNode col : columnList.getChildren()) {
            if (!"COLUMN_REF".equals(col.getNodeType())) continue;
            String colName = col.getValue();
            if (colName == null || "*".equals(colName)) continue;

            // If the column is not in GROUP BY and not wrapped in an aggregate
            String bare = columnPart(colName).toLowerCase();
            if (!groupByCols.contains(bare)) {
                errors.add(new SemanticError(
                        "MY002",
                        t("Non-aggregated column '" + colName + "' in SELECT is not in GROUP BY",
                                "Columna no agregada '" + colName + "' en SELECT no está en GROUP BY"),
                        t("MySQL 5.x allows non-aggregated columns in GROUP BY (ONLY_FULL_GROUP_BY off); "
                                + "MySQL 8+ enforces ONLY_FULL_GROUP_BY — add '" + colName
                                + "' to GROUP BY or wrap it in an aggregate function",
                                "MySQL 5.x lo tolera con ONLY_FULL_GROUP_BY apagado; MySQL 8+ exige ONLY_FULL_GROUP_BY — "
                                + "añada '" + colName + "' a GROUP BY o agréguela."),
                        SemanticError.Severity.WARNING));
            }
        }
    }

    // -------------------------------------------------------------------------
    // MY003 — LIMIT without ORDER BY
    // -------------------------------------------------------------------------

    private void checkLimitWithoutOrderBy(List<SemanticError> errors) {
        if (!"SELECT_STATEMENT".equals(root.getNodeType())) return;

        AstNode limit   = findChild(root, "LIMIT");
        AstNode orderBy = findChild(root, "ORDER_BY");

        if (limit != null && orderBy == null) {
            errors.add(new SemanticError(
                    "MY003",
                    t("LIMIT without ORDER BY returns non-deterministic results",
                            "LIMIT sin ORDER BY devuelve resultados no deterministas"),
                    t("Add an ORDER BY clause so MySQL returns rows in a predictable order",
                            "Añada ORDER BY para un orden predecible de filas"),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // MY004 — Deprecated MySQL functions
    // -------------------------------------------------------------------------

    private void checkDeprecatedFunctions(List<SemanticError> errors) {
        if (rawSqlMatches(DEPRECATED_PASSWORD_FN)) {
            errors.add(new SemanticError(
                    "MY004",
                    t("Function PASSWORD() is deprecated in MySQL 8.0", "PASSWORD() está obsoleto en MySQL 8.0"),
                    t("Use a proper password-hashing mechanism such as bcrypt outside the database layer; "
                            + "PASSWORD() was removed in MySQL 8.0",
                        "Use un hash de contraseñas adecuado (p.ej. bcrypt) fuera del motor; "
                                + "PASSWORD() se eliminó en MySQL 8.0"),
                    SemanticError.Severity.WARNING));
        }

        if (rawSqlMatches(DEPRECATED_ENCRYPT_FN)) {
            errors.add(new SemanticError(
                    "MY004",
                    t("Function ENCRYPT() is deprecated in MySQL 8.0", "ENCRYPT() está obsoleto en MySQL 8.0"),
                    t("ENCRYPT() relies on the system's crypt() call and was removed in MySQL 8.0; "
                            + "use AES_ENCRYPT() or handle encryption at the application level",
                        "ENCRYPT() dependía de crypt() del SO y se eliminó en 8.0; "
                                + "use AES_ENCRYPT() o la capa de aplicación"),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // MY005 — JSON_TABLE (MySQL 8.0+)
    // -------------------------------------------------------------------------

    private void checkJsonTable(List<SemanticError> errors) {
        if (rawSqlMatches(JSON_TABLE_FN)) {
            errors.add(new SemanticError(
                    "MY005",
                    t("JSON_TABLE requires MySQL 8.0 or later", "JSON_TABLE requiere MySQL 8.0 o superior"),
                    t("JSON_TABLE() was introduced in MySQL 8.0; ensure your server version supports it "
                            + "before deploying this query",
                        "JSON_TABLE() llegó en MySQL 8.0; compruebe la versión del servidor"),
                    SemanticError.Severity.INFO));
        }
    }

    // -------------------------------------------------------------------------
    // MY006 — AUTO_INCREMENT on a column that is not a PRIMARY KEY
    // -------------------------------------------------------------------------

    /**
     * MySQL requires AUTO_INCREMENT columns to be indexed (usually as PRIMARY KEY).
     * This check scans CREATE TABLE AST nodes for COLUMN_DEF entries that have
     * an AUTO_INCREMENT constraint but no inline PRIMARY KEY constraint, and where
     * no table-level PRIMARY KEY constraint is detected referencing that column.
     */
    private void checkAutoIncrementOnNonPk(List<SemanticError> errors) {
        List<AstNode> createTables = findAllChildrenDeep(root, "CREATE_TABLE_STATEMENT");
        // root itself may be the CREATE TABLE statement
        if ("CREATE_TABLE_STATEMENT".equals(root.getNodeType())) {
            createTables = new java.util.ArrayList<>(createTables);
            if (!createTables.contains(root)) createTables.add(0, root);
        }

        for (AstNode createTable : createTables) {
            AstNode columnDefs = findChild(createTable, "COLUMN_DEFINITIONS");
            if (columnDefs == null) continue;

            // Collect table-level PK column names
            Set<String> pkCols = collectTableLevelPkColumns(columnDefs);

            for (AstNode child : columnDefs.getChildren()) {
                if (!"COLUMN_DEF".equals(child.getNodeType())) continue;

                String colName   = child.getValue();
                boolean hasAutoInc = hasConstraint(child, "AUTO_INCREMENT");
                boolean hasInlinePk = hasConstraint(child, "PRIMARY KEY");

                if (hasAutoInc && !hasInlinePk && !pkCols.contains(colName.toLowerCase())) {
                    errors.add(new SemanticError(
                            "MY006",
                            t("AUTO_INCREMENT column '" + colName + "' must be defined as a key",
                                    "Columna AUTO_INCREMENT '" + colName + "' debe ser clave"),
                            t("In MySQL, every AUTO_INCREMENT column must be a PRIMARY KEY or part of "
                                    + "a UNIQUE index. Add PRIMARY KEY to '" + colName
                                    + "' or declare it in a PRIMARY KEY constraint",
                                "En MySQL, AUTO_INCREMENT debe ser PRIMARY KEY o parte de UNIQUE. "
                                        + "Añada PRIMARY KEY a '" + colName + "' o restricción adecuada"),
                            SemanticError.Severity.ERROR));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Collects column names referenced by table-level PRIMARY KEY constraints. */
    private Set<String> collectTableLevelPkColumns(AstNode columnDefs) {
        Set<String> pkCols = new java.util.HashSet<>();
        for (AstNode child : columnDefs.getChildren()) {
            if (!"TABLE_CONSTRAINT".equals(child.getNodeType())) continue;
            AstNode constraintType = findChild(child, "CONSTRAINT");
            if (constraintType == null || !"PRIMARY KEY".equals(constraintType.getValue())) continue;
            // Collect all COLUMN_REF children of the COLUMN_LIST inside this constraint
            AstNode colList = findChild(child, "COLUMN_LIST");
            if (colList == null) continue;
            for (AstNode colRef : colList.getChildren()) {
                if (colRef.getValue() != null) pkCols.add(colRef.getValue().toLowerCase());
            }
        }
        return pkCols;
    }

    /** Returns true if the COLUMN_DEF node has an inline constraint matching {@code constraintValue}. */
    private boolean hasConstraint(AstNode colDef, String constraintValue) {
        for (AstNode child : colDef.getChildren()) {
            if ("CONSTRAINT".equals(child.getNodeType())
                    && constraintValue.equalsIgnoreCase(child.getValue())) {
                return true;
            }
        }
        return false;
    }
}
