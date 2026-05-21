package com.qwerys.qwerys_backend.analyzer.procedural;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.MySQLDialectAnalyzer;
import com.qwerys.qwerys_backend.analyzer.SemanticError;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL SQL/PSM–specific procedural checks for stored procedures, functions, and triggers.
 *
 * <p>Rule identifiers: {@code PROC-MY-001} … {@code PROC-MY-008}.
 */
public class MySqlPsmAnalyzer extends MySQLDialectAnalyzer {

    private static final Pattern CREATE_PROCEDURE =
            Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?PROCEDURE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_FUNCTION =
            Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TRIGGER =
            Pattern.compile("\\bCREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DETERMINISTIC_DECL =
            Pattern.compile("\\b(?:NOT\\s+)?DETERMINISTIC\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_SECURITY =
            Pattern.compile("\\bSQL\\s+SECURITY\\s+(?:DEFINER|INVOKER)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DECLARE_CURSOR =
            Pattern.compile("(?is)\\bDECLARE\\s+\\w+\\s+CURSOR\\b");
    private static final Pattern FETCH_INTO =
            Pattern.compile("(?is)\\bFETCH\\s+\\w+\\s+INTO\\b");
    private static final Pattern HANDLER_NOT_FOUND =
            Pattern.compile("(?is)\\bHANDLER\\s+FOR\\s+[^;]*\\bNOT\\s+FOUND\\b");
    private static final Pattern HANDLER_SQLSTATE_02000 =
            Pattern.compile("(?is)\\bHANDLER\\s+FOR\\s+[^;]*SQLSTATE\\s+'02000'");
    private static final Pattern HANDLER_1329 =
            Pattern.compile("(?is)\\bHANDLER\\s+FOR\\s+[^;]*\\b1329\\b");

    private static final Pattern EXIT_HANDLER_SQLEXCEPTION =
            Pattern.compile("(?is)\\bDECLARE\\s+EXIT\\s+HANDLER\\s+FOR\\s+[^;]*\\bSQLEXCEPTION\\b");

    private static final Pattern SIGNAL_STMT =
            Pattern.compile("(?is)\\bSIGNAL\\s+");
    private static final Pattern SIGNAL_SQLSTATE =
            Pattern.compile("(?is)\\bSIGNAL\\s+SQLSTATE\\b");

    private static final Pattern TRIGGER_ON_TABLE =
            Pattern.compile("(?is)\\bON\\s+`?([\\w.]+)`?\\s+(?:FOR\\s+EACH\\s+ROW\\s+)?BEGIN");

    private static final Pattern MODIFIES_SQL_DATA =
            Pattern.compile("\\bMODIFIES\\s+SQL\\s+DATA\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern OUT_PARAM =
            Pattern.compile("\\bOUT\\s+`?(\\w+)`?\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern UPDATE_TABLE =
            Pattern.compile("(?is)\\bUPDATE\\s+(?:IGNORE\\s+)?`?([\\w.]+)`?(?:\\s+|\\()");

    public MySqlPsmAnalyzer(AstNode root, String rawSql) {
        this(root, rawSql, Locale.ENGLISH);
    }

    public MySqlPsmAnalyzer(AstNode root, String rawSql, Locale uiLocale) {
        super(root, rawSql, uiLocale != null ? uiLocale : Locale.ENGLISH);
    }

    @Override
    protected void analyzeDialectSpecific(List<SemanticError> errors) {
        super.analyzeDialectSpecific(errors);
        if (rawSql == null || rawSql.isBlank()) {
            return;
        }
        if (!isPsmContext()) {
            return;
        }
        checkProcMy001(errors);
        checkProcMy002(errors);
        checkProcMy003(errors);
        checkProcMy004(errors);
        checkProcMy005(errors);
        checkProcMy006(errors);
        checkProcMy007(errors);
        checkProcMy008(errors);
    }

    private boolean isPsmContext() {
        String t = root.getNodeType();
        if (t != null && (t.equals("CREATE_PROCEDURE_STATEMENT")
                || t.equals("CREATE_FUNCTION_STATEMENT")
                || t.equals("CREATE_TRIGGER_STATEMENT")
                || t.equals("BLOCK_STATEMENT"))) {
            return true;
        }
        String u = rawSql.stripLeading().toUpperCase(Locale.ROOT);
        return u.startsWith("DECLARE")
                || (u.startsWith("BEGIN") && !u.startsWith("BEGIN TRANSACTION") && !u.startsWith("BEGIN WORK"))
                || CREATE_PROCEDURE.matcher(rawSql).find()
                || CREATE_FUNCTION.matcher(rawSql).find()
                || CREATE_TRIGGER.matcher(rawSql).find();
    }

    /** PROC-MY-001: procedure without DETERMINISTIC / NOT DETERMINISTIC (replication-sensitive). */
    private void checkProcMy001(List<SemanticError> errors) {
        if (!CREATE_PROCEDURE.asPredicate().test(rawSql)) {
            return;
        }
        if (DETERMINISTIC_DECL.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-MY-001",
                t("Procedure without DETERMINISTIC / NOT DETERMINISTIC clause",
                        "Procedimiento sin cláusula DETERMINISTIC / NOT DETERMINISTIC"),
                t("MySQL uses these declarations to classify routines for binlog/replication; add NOT DETERMINISTIC or DETERMINISTIC as appropriate.",
                        "MySQL usa estas declaraciones para clasificar rutinas en binlog/replicación; añade NOT DETERMINISTIC o DETERMINISTIC según el comportamiento real."),
                SemanticError.Severity.WARNING));
    }

    /** PROC-MY-002: SQL SECURITY omitted (defaults to DEFINER). */
    private void checkProcMy002(List<SemanticError> errors) {
        if (!CREATE_PROCEDURE.asPredicate().test(rawSql)
                && !CREATE_FUNCTION.asPredicate().test(rawSql)
                && !CREATE_TRIGGER.asPredicate().test(rawSql)) {
            return;
        }
        if (SQL_SECURITY.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-MY-002",
                t("SQL SECURITY not specified (defaults to DEFINER)",
                        "SQL SECURITY no especificado (por defecto DEFINER)"),
                t("Default is DEFINER; if the routine must run with invoker privileges, use SQL SECURITY INVOKER.",
                        "Valor por defecto es DEFINER; si la rutina debe ejecutarse con privilegios del invocador, usa SQL SECURITY INVOKER."),
                SemanticError.Severity.INFO));
    }

    /** PROC-MY-003: FETCH cursor without NOT FOUND handler. */
    private void checkProcMy003(List<SemanticError> errors) {
        if (!FETCH_INTO.matcher(rawSql).find() || !DECLARE_CURSOR.matcher(rawSql).find()) {
            return;
        }
        if (HANDLER_NOT_FOUND.matcher(rawSql).find()
                || HANDLER_SQLSTATE_02000.matcher(rawSql).find()
                || HANDLER_1329.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-MY-003",
                t("Loop may never terminate (FETCH without NOT FOUND handler)",
                        "Loop nunca termina"),
                t("Declare HANDLER FOR NOT FOUND (or SQLSTATE '02000') before FETCH to exit when no rows remain.",
                        "Declare HANDLER FOR NOT FOUND (o SQLSTATE '02000') antes del FETCH para salir del bucle cuando no haya filas."),
                SemanticError.Severity.ERROR));
    }

    /** PROC-MY-004: EXIT handler for SQLEXCEPTION — often CONTINUE is safer for scoped handling. */
    private void checkProcMy004(List<SemanticError> errors) {
        if (!EXIT_HANDLER_SQLEXCEPTION.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-MY-004",
                t("EXIT HANDLER FOR SQLEXCEPTION — consider CONTINUE",
                        "EXIT HANDLER FOR SQLEXCEPTION — considere CONTINUE"),
                t("EXIT ends the current block on first error; CONTINUE may allow logging without aborting the whole flow when appropriate.",
                        "EXIT termina el bloque actual ante el primer error; CONTINUE puede permitir registro/localización sin abortar todo el flujo si aplica."),
                SemanticError.Severity.INFO));
    }

    /** PROC-MY-005: SIGNAL without SQLSTATE. */
    private void checkProcMy005(List<SemanticError> errors) {
        Matcher m = SIGNAL_STMT.matcher(rawSql);
        while (m.find()) {
            int from = m.end();
            int endStmt = indexOfStatementEnd(rawSql, from);
            String fragment = rawSql.substring(from, Math.min(rawSql.length(), endStmt > 0 ? endStmt : rawSql.length()));
            if (!SIGNAL_SQLSTATE.matcher(fragment).find()) {
                errors.add(new SemanticError(
                        "PROC-MY-005",
                        t("SIGNAL without SQLSTATE", "SIGNAL sin SQLSTATE"),
                        t("Use SIGNAL SQLSTATE '45000' or another valid 5-character SQLSTATE.",
                                "Use SIGNAL SQLSTATE '45000' u otro SQLSTATE de 5 caracteres válido."),
                        SemanticError.Severity.ERROR));
                return;
            }
        }
    }

    private static int indexOfStatementEnd(String sql, int from) {
        int i = from;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == ';') {
                return i;
            }
            if (c == '\'' || c == '"') {
                char q = c;
                i++;
                while (i < sql.length()) {
                    if (sql.charAt(i) == q) {
                        if (q == '\'' && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            i++;
        }
        return sql.length();
    }

    /** PROC-MY-006: trigger updates same table as ON clause (non-recursive engines). */
    private void checkProcMy006(List<SemanticError> errors) {
        if (!CREATE_TRIGGER.asPredicate().test(rawSql)) {
            return;
        }
        Matcher mt = TRIGGER_ON_TABLE.matcher(rawSql);
        if (!mt.find()) {
            return;
        }
        String table = normalizeTableName(mt.group(1));
        Matcher mu = UPDATE_TABLE.matcher(rawSql);
        while (mu.find()) {
            if (normalizeTableName(mu.group(1)).equals(table)) {
                errors.add(new SemanticError(
                        "PROC-MY-006",
                        t("Impossible recursion", "Recursión imposible"),
                        t("MySQL does not allow a trigger to update the same table under FOR EACH ROW in the same operation.",
                                "MySQL no permite que un trigger actualice la misma tabla bajo FOR EACH ROW en la misma operación."),
                        SemanticError.Severity.ERROR));
                return;
            }
        }
    }

    private static String normalizeTableName(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return (dot >= 0 ? name.substring(dot + 1) : name).replace("`", "")
                .toLowerCase(Locale.ROOT);
    }

    /** PROC-MY-007: function declares MODIFIES SQL DATA. */
    private void checkProcMy007(List<SemanticError> errors) {
        if (!CREATE_FUNCTION.asPredicate().test(rawSql)) {
            return;
        }
        if (!MODIFIES_SQL_DATA.matcher(rawSql).find()) {
            return;
        }
        errors.add(new SemanticError(
                "PROC-MY-007",
                t("Function should use READS SQL DATA or NO SQL", "Function debería ser READS SQL DATA o NO SQL"),
                t("Avoid MODIFIES SQL DATA in stored functions when possible to align with read-only usage.",
                        "Evite MODIFIES SQL DATA en funciones almacenadas cuando sea posible para alinear el contrato con uso de solo lectura."),
                SemanticError.Severity.WARNING));
    }

    /** PROC-MY-008: OUT parameter never assigned in body. */
    private void checkProcMy008(List<SemanticError> errors) {
        if (!CREATE_PROCEDURE.asPredicate().test(rawSql)) {
            return;
        }
        int beginIdx = indexOfIgnoreCase(rawSql, "BEGIN");
        if (beginIdx < 0) {
            return;
        }
        String head = rawSql.substring(0, beginIdx);
        String body = rawSql.substring(beginIdx);
        List<String> outs = new ArrayList<>();
        Matcher mp = OUT_PARAM.matcher(head);
        while (mp.find()) {
            outs.add(mp.group(1).toLowerCase(Locale.ROOT));
        }
        if (outs.isEmpty()) {
            return;
        }
        for (String p : outs) {
            if (!isOutParamAssigned(body, p)) {
                errors.add(new SemanticError(
                        "PROC-MY-008",
                        t("OUT parameter not assigned: " + p, "Parámetro OUT no asignado: " + p),
                        t("Assign the OUT parameter with SET or SELECT … INTO before exiting the procedure.",
                                "Asigne el parámetro OUT con SET o SELECT … INTO antes de salir del procedimiento."),
                        SemanticError.Severity.ERROR));
            }
        }
    }

    private static boolean isOutParamAssigned(String body, String param) {
        String p = Pattern.quote(param);
        if (Pattern.compile("(?is)\\bSET\\s+" + p + "\\s*=").matcher(body).find()) {
            return true;
        }
        if (Pattern.compile("(?is)\\bSELECT\\s+[^;]*\\bINTO\\s+[^;]*\\b" + p + "\\b").matcher(body).find()) {
            return true;
        }
        return false;
    }

    private static int indexOfIgnoreCase(String s, String sub) {
        return s.toLowerCase(Locale.ROOT).indexOf(sub.toLowerCase(Locale.ROOT));
    }
}
