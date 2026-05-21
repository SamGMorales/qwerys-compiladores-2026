package com.qwerys.qwerys_backend.analyzer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Semantic analyzer for SQLite-specific rules.
 * Extends the generic {@link SemanticAnalyzer} with checks that are only
 * relevant when targeting an SQLite database.
 *
 * <p>Rule codes use the prefix {@code LT} (SqLiTe).
 */
public class SQLiteDialectAnalyzer extends SemanticAnalyzer {

    // -------------------------------------------------------------------------
    // Patterns
    // -------------------------------------------------------------------------

    private static final Pattern RIGHT_JOIN_PATTERN =
            Pattern.compile("\\bRIGHT\\s+(OUTER\\s+)?JOIN\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern FULL_OUTER_JOIN_PATTERN =
            Pattern.compile("\\bFULL\\s+(OUTER\\s+)?JOIN\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_TABLE_PATTERN =
            Pattern.compile("\\bALTER\\s+TABLE\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern ALTER_DROP_COLUMN =
            Pattern.compile("\\bALTER\\s+TABLE\\b.+?\\bDROP\\s+COLUMN\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ALTER_RENAME_COLUMN =
            Pattern.compile("\\bALTER\\s+TABLE\\b.+?\\bRENAME\\s+COLUMN\\b",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern DATETIME_TYPE_PATTERN =
            Pattern.compile("\\b(DATETIME|TIMESTAMP|DATE|TIME)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern WINDOW_FUNCTION_PATTERN =
            Pattern.compile("\\bOVER\\s*\\(", Pattern.CASE_INSENSITIVE);

    private static final Pattern WITHOUT_ROWID_PATTERN =
            Pattern.compile("\\bWITHOUT\\s+ROWID\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern FOREIGN_KEY_PRAGMA =
            Pattern.compile("\\bPRAGMA\\s+foreign_keys\\s*=\\s*ON\\b", Pattern.CASE_INSENSITIVE);

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public SQLiteDialectAnalyzer(AstNode root) {
        super(root, null, Locale.ENGLISH);
    }

    public SQLiteDialectAnalyzer(AstNode root, String rawSql) {
        super(root, rawSql, Locale.ENGLISH);
    }

    public SQLiteDialectAnalyzer(AstNode root, String rawSql, Locale uiLocale) {
        super(root, rawSql, uiLocale);
    }

    // -------------------------------------------------------------------------
    // Dialect-specific checks
    // -------------------------------------------------------------------------

    @Override
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        checkUnsupportedJoins(errors);
        checkUnsupportedAlterTable(errors);
        checkForeignKeyWithoutPragma(errors);
        checkDateTimeTypeAffinity(errors);
        checkWindowFunctions(errors);
        checkWithoutRowid(errors);
    }

    // -------------------------------------------------------------------------
    // LT001 — RIGHT JOIN and FULL OUTER JOIN not supported
    // -------------------------------------------------------------------------

    private void checkUnsupportedJoins(List<SemanticError> errors) {
        if (rawSqlMatches(RIGHT_JOIN_PATTERN)) {
            errors.add(new SemanticError(
                    "LT001",
                    t("SQLite does not support RIGHT JOIN", "SQLite no admite RIGHT JOIN"),
                    t("Rewrite using LEFT JOIN with the table order reversed: "
                            + "instead of 'A RIGHT JOIN B ON ...', write 'B LEFT JOIN A ON ...'",
                            "Reescriba con LEFT JOIN invirtiendo tablas: 'B LEFT JOIN A ON ...'"),
                    SemanticError.Severity.ERROR));
        }

        if (rawSqlMatches(FULL_OUTER_JOIN_PATTERN)) {
            errors.add(new SemanticError(
                    "LT001",
                    t("SQLite does not support FULL OUTER JOIN", "SQLite no admite FULL OUTER JOIN"),
                    t("Emulate FULL OUTER JOIN with: (SELECT ... FROM A LEFT JOIN B ...) "
                            + "UNION ALL (SELECT ... FROM B LEFT JOIN A ... WHERE A.id IS NULL)",
                            "Emule con UNION ALL de dos LEFT JOIN complementarios"),
                    SemanticError.Severity.ERROR));
        }

        // Also check AST JOIN nodes
        List<AstNode> joins = findAllChildrenDeep(root, "JOIN");
        for (AstNode join : joins) {
            String joinType = join.getValue();
            if (joinType == null) continue;
            String upper = joinType.toUpperCase();
            if (upper.contains("RIGHT")) {
                errors.add(new SemanticError(
                        "LT001",
                        t("SQLite does not support RIGHT JOIN; use LEFT JOIN with reversed table order",
                                "SQLite no admite RIGHT JOIN; use LEFT JOIN con tablas invertidas"),
                        t("Swap the table positions and use LEFT JOIN instead",
                                "Intercambie el orden de tablas y use LEFT JOIN"),
                        SemanticError.Severity.ERROR));
            }
            if (upper.contains("FULL")) {
                errors.add(new SemanticError(
                        "LT001",
                        t("SQLite does not support FULL OUTER JOIN; use LEFT JOIN with reversed table order",
                                "SQLite no admite FULL OUTER JOIN; emule con LEFT JOIN"),
                        t("Emulate with UNION ALL of two LEFT JOINs",
                                "Emule con UNION ALL de dos LEFT JOIN"),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    // -------------------------------------------------------------------------
    // LT002 — ALTER TABLE with unsupported operations
    // -------------------------------------------------------------------------

    /**
     * SQLite ALTER TABLE only supports:
     *   - ADD COLUMN (always)
     *   - DROP COLUMN (SQLite 3.35+)
     *   - RENAME COLUMN (SQLite 3.25+)
     *   - RENAME TABLE (always)
     *
     * Dropping or renaming columns on older SQLite requires recreating the table.
     */
    private void checkUnsupportedAlterTable(List<SemanticError> errors) {
        if (!rawSqlMatches(ALTER_TABLE_PATTERN)) return;

        if (rawSqlMatches(ALTER_DROP_COLUMN)) {
            errors.add(new SemanticError(
                    "LT002",
                    t("ALTER TABLE ... DROP COLUMN requires SQLite 3.35 or later",
                            "ALTER TABLE ... DROP COLUMN requiere SQLite 3.35 o superior"),
                    t("On older SQLite versions, use the table recreation pattern: "
                            + "CREATE TABLE new_table AS SELECT <desired columns> FROM old_table; "
                            + "DROP TABLE old_table; ALTER TABLE new_table RENAME TO old_table",
                            "En versiones antiguas recree la tabla: CREATE AS SELECT, DROP, RENAME"),
                    SemanticError.Severity.ERROR));
        }

        if (rawSqlMatches(ALTER_RENAME_COLUMN)) {
            errors.add(new SemanticError(
                    "LT002",
                    t("ALTER TABLE ... RENAME COLUMN requires SQLite 3.25 or later",
                            "ALTER TABLE ... RENAME COLUMN requiere SQLite 3.25 o superior"),
                    t("On older SQLite versions, rename columns by recreating the table with the new schema",
                            "En versiones antiguas renombre recreando la tabla con el esquema nuevo"),
                    SemanticError.Severity.ERROR));
        }
    }

    // -------------------------------------------------------------------------
    // LT003 — Foreign keys require PRAGMA foreign_keys = ON
    // -------------------------------------------------------------------------

    /**
     * SQLite foreign key enforcement is disabled by default.
     * If a CREATE TABLE contains FOREIGN KEY constraints, warn that the pragma is required.
     */
    private void checkForeignKeyWithoutPragma(List<SemanticError> errors) {
        boolean hasForeignKey = false;

        List<AstNode> createTables = findAllChildrenDeep(root, "CREATE_TABLE_STATEMENT");
        if ("CREATE_TABLE_STATEMENT".equals(root.getNodeType())) {
            createTables = new java.util.ArrayList<>(createTables);
            if (!createTables.contains(root)) createTables.add(0, root);
        }

        for (AstNode createTable : createTables) {
            AstNode columnDefs = findChild(createTable, "COLUMN_DEFINITIONS");
            if (columnDefs == null) continue;
            for (AstNode child : columnDefs.getChildren()) {
                if ("TABLE_CONSTRAINT".equals(child.getNodeType())) {
                    AstNode constraint = findChild(child, "CONSTRAINT");
                    if (constraint != null && "FOREIGN KEY".equals(constraint.getValue())) {
                        hasForeignKey = true;
                        break;
                    }
                }
                if ("COLUMN_DEF".equals(child.getNodeType())) {
                    AstNode refs = findChild(child, "REFERENCES");
                    if (refs != null) {
                        hasForeignKey = true;
                        break;
                    }
                }
            }
        }

        if (hasForeignKey && !rawSqlMatches(FOREIGN_KEY_PRAGMA)) {
            errors.add(new SemanticError(
                    "LT003",
                    t("SQLite foreign keys are disabled by default",
                            "Las claves foráneas de SQLite están desactivadas por defecto"),
                    t("Run 'PRAGMA foreign_keys = ON' at the start of each connection to enable "
                            + "foreign key enforcement in SQLite",
                            "Ejecute 'PRAGMA foreign_keys = ON' al inicio de cada conexión"),
                    SemanticError.Severity.WARNING));
        }
    }

    // -------------------------------------------------------------------------
    // LT004 — Date/time type affinity
    // -------------------------------------------------------------------------

    private void checkDateTimeTypeAffinity(List<SemanticError> errors) {
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
                if (type != null && DATETIME_TYPE_PATTERN.matcher(type).find()) {
                    found = true;
                    break outer;
                }
            }
        }

        if (!found && rawSqlMatches(DATETIME_TYPE_PATTERN)) found = true;

        if (found) {
            errors.add(new SemanticError(
                    "LT004",
                    t("SQLite uses type affinity — DATETIME is stored as TEXT, REAL, or INTEGER",
                            "SQLite usa afinidad de tipo — DATETIME puede ser TEXT, REAL o INTEGER"),
                    t("SQLite has no native date/time type. Store dates as TEXT (ISO 8601), "
                            + "REAL (Julian day), or INTEGER (Unix timestamp). "
                            + "Use date() / datetime() / strftime() functions for comparisons",
                            "No hay tipo fecha nativo. Guarde ISO 8601, día juliano o Unix time. "
                                    + "Use date()/datetime()/strftime() para comparar"),
                    SemanticError.Severity.INFO));
        }
    }

    // -------------------------------------------------------------------------
    // LT005 — Window functions require SQLite 3.25+
    // -------------------------------------------------------------------------

    private void checkWindowFunctions(List<SemanticError> errors) {
        if (!rawSqlMatches(WINDOW_FUNCTION_PATTERN)) return;

        errors.add(new SemanticError(
                "LT005",
                t("Window functions (OVER clause) require SQLite 3.25 or later",
                        "Funciones de ventana (OVER) requieren SQLite 3.25 o superior"),
                t("Window functions such as ROW_NUMBER() OVER (...) were added in SQLite 3.25.0 (2018). "
                        + "Verify your SQLite version with SELECT sqlite_version()",
                        "ROW_NUMBER() OVER (...) llegó en 3.25.0. Verifique con SELECT sqlite_version()"),
                SemanticError.Severity.INFO));
    }

    // -------------------------------------------------------------------------
    // LT006 — WITHOUT ROWID table must have a PRIMARY KEY
    // -------------------------------------------------------------------------

    private void checkWithoutRowid(List<SemanticError> errors) {
        List<AstNode> createTables = findAllChildrenDeep(root, "CREATE_TABLE_STATEMENT");
        if ("CREATE_TABLE_STATEMENT".equals(root.getNodeType())) {
            createTables = new java.util.ArrayList<>(createTables);
            if (!createTables.contains(root)) createTables.add(0, root);
        }

        for (AstNode createTable : createTables) {
            AstNode withoutRowid = findChild(createTable, "WITHOUT_ROWID");
            if (withoutRowid == null) continue;

            boolean hasPk = false;
            AstNode columnDefs = findChild(createTable, "COLUMN_DEFINITIONS");
            if (columnDefs != null) {
                for (AstNode child : columnDefs.getChildren()) {
                    // Inline PRIMARY KEY
                    if ("COLUMN_DEF".equals(child.getNodeType())) {
                        for (AstNode c : child.getChildren()) {
                            if ("CONSTRAINT".equals(c.getNodeType())
                                    && "PRIMARY KEY".equals(c.getValue())) {
                                hasPk = true;
                                break;
                            }
                        }
                    }
                    // Table-level PRIMARY KEY constraint
                    if ("TABLE_CONSTRAINT".equals(child.getNodeType())) {
                        AstNode constraint = findChild(child, "CONSTRAINT");
                        if (constraint != null && "PRIMARY KEY".equals(constraint.getValue())) {
                            hasPk = true;
                        }
                    }
                    if (hasPk) break;
                }
            }

            if (!hasPk) {
                errors.add(new SemanticError(
                        "LT006",
                        t("WITHOUT ROWID table must have a PRIMARY KEY defined",
                                "Tabla WITHOUT ROWID debe tener PRIMARY KEY"),
                        t("WITHOUT ROWID tables require an explicit PRIMARY KEY. "
                                + "Add a PRIMARY KEY constraint to the CREATE TABLE statement",
                                "Añada PRIMARY KEY explícito en el CREATE TABLE"),
                        SemanticError.Severity.ERROR));
            }
        }
    }
}
