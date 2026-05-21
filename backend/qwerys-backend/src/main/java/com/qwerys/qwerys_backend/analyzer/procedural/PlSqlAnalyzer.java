package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.OracleDialectAnalyzer;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle PL/SQL–specific procedural rules on top of {@link OracleDialectAnalyzer}.
 */
public class PlSqlAnalyzer extends OracleDialectAnalyzer {

    private static final Pattern CREATE_SUBPROGRAM =
            Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern AUTHID_CLAUSE =
            Pattern.compile("\\bAUTHID\\s+(?:CURRENT_USER|DEFINER)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SELECT_INTO =
            Pattern.compile("\\bSELECT\\s+.+?\\bINTO\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern EXCEPTION_WHEN_PREDEF =
            Pattern.compile("\\bWHEN\\s+(?:NO_DATA_FOUND|TOO_MANY_ROWS|OTHERS)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PERCENT_TYPE_TABLE_REF =
            Pattern.compile("\\b([A-Za-z_]\\w*)\\s*\\.\\s*([A-Za-z_]\\w*)\\s*%\\s*TYPE\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_TRIGGER =
            Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PRAGMA_AUTONOMOUS =
            Pattern.compile("\\bPRAGMA\\s+AUTONOMOUS_TRANSACTION\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMIT_ROLLBACK =
            Pattern.compile("\\b(COMMIT|ROLLBACK)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern BULK_COLLECT =
            Pattern.compile("\\bBULK\\s+COLLECT\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern LIMIT_KW = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern FORALL_KW = Pattern.compile("\\bFORALL\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SAVE_EXCEPTIONS =
            Pattern.compile("SAVE\\s+EXCEPTIONS", Pattern.CASE_INSENSITIVE);

    private static final Pattern CURSOR_DECL =
            Pattern.compile("\\bCURSOR\\s+(\\w+)\\s+(?:IS|FOR)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern OPEN_CURSOR = Pattern.compile("\\bOPEN\\s+(\\w+)\\b",
            Pattern.CASE_INSENSITIVE);

    private final Set<String> schemaTables;

    public PlSqlAnalyzer(AstNode root, String rawSql) {
        this(root, rawSql, null, Locale.ENGLISH);
    }

    /**
     * @param schemaTables optional catalog of known table names (without schema) for {@code %TYPE} checks
     */
    public PlSqlAnalyzer(AstNode root, String rawSql, Set<String> schemaTables) {
        this(root, rawSql, schemaTables, Locale.ENGLISH);
    }

    public PlSqlAnalyzer(AstNode root, String rawSql, Set<String> schemaTables, Locale uiLocale) {
        super(root, rawSql, uiLocale != null ? uiLocale : Locale.ENGLISH);
        this.schemaTables = schemaTables;
    }

    @Override
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        super.analyzeDialectSpecific(errors);
        if (rawSql == null || rawSql.isBlank()) {
            return;
        }
        checkProcOra001(errors);
        checkProcOra002(errors);
        checkProcOra003(errors);
        checkProcOra004(errors);
        checkProcOra005(errors);
        checkProcOra006(errors);
        checkProcOra007(errors);
        checkProcOra008(errors);
    }

    private void checkProcOra001(List<SemanticError> errors) {
        if (!CREATE_SUBPROGRAM.matcher(rawSql).find()) {
            return;
        }
        if (AUTHID_CLAUSE.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-ORA-001",
                t("Subprogram without explicit AUTHID", "Subprograma sin AUTHID explícita"),
                t("Consider AUTHID CURRENT_USER (invoker) versus AUTHID DEFINER (owner) for runtime privilege behavior.",
                        "Considera AUTHID CURRENT_USER (invoker) versus AUTHID DEFINER (owner) para el comportamiento "
                                + "de privilegios en runtime."),
                SemanticError.Severity.INFO));
    }

    private void checkProcOra002(List<SemanticError> errors) {
        if (!SELECT_INTO.matcher(rawSql).find()) {
            return;
        }
        if (!Pattern.compile("\\bEXCEPTION\\b", Pattern.CASE_INSENSITIVE).matcher(rawSql).find()) {
            errors.add(new SemanticError(
                    "PROC-ORA-002",
                    t("SELECT INTO without EXCEPTION handler for NO_DATA_FOUND / TOO_MANY_ROWS",
                            "SELECT INTO sin manejador EXCEPTION para NO_DATA_FOUND / TOO_MANY_ROWS"),
                    t("Add an EXCEPTION block with WHEN NO_DATA_FOUND, WHEN TOO_MANY_ROWS, or WHEN OTHERS, "
                            + "or handle SQL%ROWCOUNT after implicit INTO.",
                            "Añade un bloque EXCEPTION con WHEN NO_DATA_FOUND, WHEN TOO_MANY_ROWS o WHEN OTHERS, "
                                    + "o maneja SQL%ROWCOUNT tras el INTO implícito."),
                    SemanticError.Severity.WARNING));
            return;
        }
        if (EXCEPTION_WHEN_PREDEF.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-ORA-002",
                t("SELECT INTO with EXCEPTION that does not cover NO_DATA_FOUND / TOO_MANY_ROWS",
                        "SELECT INTO con EXCEPTION que no cubre NO_DATA_FOUND / TOO_MANY_ROWS"),
                t("You typically need WHEN NO_DATA_FOUND and often WHEN TOO_MANY_ROWS for SELECT ... INTO.",
                        "Normalmente se necesita WHEN NO_DATA_FOUND y a menudo WHEN TOO_MANY_ROWS para SELECT ... INTO."),
                SemanticError.Severity.WARNING));
    }

    private void checkProcOra003(List<SemanticError> errors) {
        if (schemaTables == null || schemaTables.isEmpty()) {
            return;
        }
        Matcher m = PERCENT_TYPE_TABLE_REF.matcher(rawSql);
        while (m.find()) {
            String table = m.group(1).toLowerCase(Locale.ROOT);
            if (!schemaTables.contains(table)) {
                errors.add(new SemanticError(
                        "PROC-ORA-003",
                        t("%TYPE references a table not in the available schema: " + m.group(1),
                                "%TYPE referencia una tabla no presente en el esquema disponible: " + m.group(1)),
                        t("Verify the table name or update the schema catalog for analysis.",
                                "Verifica el nombre de tabla o actualiza el catálogo de esquema para el análisis."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private void checkProcOra004(List<SemanticError> errors) {
        if (!CREATE_TRIGGER.matcher(rawSql).find()) {
            return;
        }
        if (Pattern.compile("\\bCOMMIT\\b", Pattern.CASE_INSENSITIVE).matcher(rawSql).find()) {
            errors.add(new SemanticError(
                    "PROC-ORA-004",
                    t("Triggers cannot COMMIT", "Triggers no pueden hacer COMMIT"),
                    t("Oracle does not allow COMMIT/ROLLBACK in non-autonomous triggers; use external transactions "
                            + "or PRAGMA AUTONOMOUS_TRANSACTION with care.",
                            "Oracle no permite COMMIT/ROLLBACK en triggers no autónomos; usa transacciones externas "
                                    + "o PRAGMA AUTONOMOUS_TRANSACTION con cuidado."),
                    SemanticError.Severity.ERROR));
        }
    }

    private void checkProcOra005(List<SemanticError> errors) {
        if (!PRAGMA_AUTONOMOUS.matcher(rawSql).find()) {
            return;
        }
        if (!COMMIT_ROLLBACK.matcher(rawSql).find()) {
            errors.add(new SemanticError(
                    "PROC-ORA-005",
                    t("PRAGMA AUTONOMOUS_TRANSACTION without visible COMMIT/ROLLBACK",
                            "PRAGMA AUTONOMOUS_TRANSACTION sin COMMIT/ROLLBACK visible"),
                    t("Autonomous transactions must end with explicit COMMIT or ROLLBACK in the autonomous unit.",
                            "Las transacciones autónomas deben finalizar con COMMIT o ROLLBACK explícito en la unidad autónoma."),
                    SemanticError.Severity.WARNING));
        }
    }

    private void checkProcOra006(List<SemanticError> errors) {
        if (!BULK_COLLECT.matcher(rawSql).find()) {
            return;
        }
        if (!LIMIT_KW.matcher(rawSql).find()) {
            errors.add(new SemanticError(
                    "PROC-ORA-006",
                    t("BULK COLLECT without LIMIT", "BULK COLLECT sin LIMIT"),
                    t("Can exhaust memory on large tables; use FETCH … BULK COLLECT … LIMIT n.",
                            "Puede agotar memoria con tablas grandes; usa FETCH … BULK COLLECT … LIMIT n."),
                    SemanticError.Severity.WARNING));
        }
    }

    private void checkProcOra007(List<SemanticError> errors) {
        if (!FORALL_KW.matcher(rawSql).find()) {
            return;
        }
        if (SAVE_EXCEPTIONS.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-ORA-007",
                t("FORALL without SAVE EXCEPTIONS", "FORALL sin SAVE EXCEPTIONS"),
                t("With heterogeneous batches, SAVE EXCEPTIONS lets you continue and inspect SQL%BULK_EXCEPTIONS.",
                        "Con conjuntos heterogéneos, SAVE EXCEPTIONS permite continuar y consultar SQL%BULK_EXCEPTIONS."),
                SemanticError.Severity.INFO));
    }

    private void checkProcOra008(List<SemanticError> errors) {
        Matcher decl = CURSOR_DECL.matcher(rawSql);
        while (decl.find()) {
            String name = decl.group(1);
            Matcher op = Pattern.compile("\\bOPEN\\s+" + Pattern.quote(name) + "\\b",
                    Pattern.CASE_INSENSITIVE).matcher(rawSql);
            if (op.find()) {
                errors.add(new SemanticError(
                        "PROC-ORA-008",
                        t("Explicit cursor; often FOR rec IN (SELECT …) LOOP is simpler",
                                "Cursor explícito; a menudo un FOR rec IN (SELECT …) LOOP es más simple"),
                        t("Implicit cursors reduce OPEN/FETCH/CLOSE when iterating a single.",
                                "Los cursores implícitos reducen código OPEN/FETCH/CLOSE cuando recorres un único SELECT."),
                        SemanticError.Severity.INFO));
                return;
            }
        }
    }
}
