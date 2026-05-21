package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.adapter.ColumnSchema;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.CqlLexer;
import com.qwerys.qwerys_backend.analyzer.CqlToken;
import com.qwerys.qwerys_backend.analyzer.CqlTokenType;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.StatementSplitter;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live-schema validation for CQL (tables + columns), including BATCH fragments and TRUNCATE.
 */
public final class CqlLiveSchemaValidator {

    private static final Set<String> CQL_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "UPDATE", "DELETE", "SET", "VALUES",
            "AND", "OR", "NOT", "IN", "USING", "TTL", "TIMESTAMP", "IF", "EXISTS", "LIMIT",
            "ALLOW", "FILTERING", "ORDER", "BY", "ASC", "DESC", "TOKEN", "COUNT", "AS",
            "BEGIN", "BATCH", "APPLY", "LOGGED", "UNLOGGED", "COUNTER", "TRUNCATE", "KEY",
            "WRITETIME", "TTL", "DISTINCT", "JSON", "NULL", "TRUE", "FALSE");

    private CqlLiveSchemaValidator() {
    }

    public static void validate(String raw, DatabaseSchema schema, Locale ui, List<SemanticError> findings) {
        if (raw == null || raw.isBlank() || schema == null) {
            return;
        }
        Map<String, TableSchema> tableIndex = SchemaValidationSupport.buildTableIndex(schema);
        if (tableIndex.isEmpty()) {
            return;
        }
        List<String> units = new ArrayList<>();
        String upper = raw.toUpperCase(Locale.ROOT);
        if (upper.contains("BEGIN") && upper.contains("BATCH")) {
            units.addAll(splitBatchInnerStatements(raw));
        }
        units.addAll(StatementSplitter.split(raw.strip(), SqlDialect.GENERIC));
        Set<String> seen = new HashSet<>();
        for (String stmt : units) {
            String one = stmt.strip();
            if (one.isEmpty()) {
                continue;
            }
            String key = one.toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                continue;
            }
            validateOneStatement(one, tableIndex, ui, findings);
        }
    }

    private static List<String> splitBatchInnerStatements(String raw) {
        List<String> out = new ArrayList<>();
        int batchIdx = raw.toUpperCase(Locale.ROOT).indexOf("BATCH");
        if (batchIdx < 0) {
            return out;
        }
        int applyIdx = raw.toUpperCase(Locale.ROOT).indexOf("APPLY", batchIdx);
        int start = applyIdx >= 0 ? applyIdx + 5 : batchIdx + 5;
        String inner = raw.substring(start).strip();
        if (inner.endsWith(";")) {
            inner = inner.substring(0, inner.length() - 1).strip();
        }
        for (String part : inner.split(";")) {
            String p = part.strip();
            if (!p.isEmpty() && !p.equalsIgnoreCase("APPLY")) {
                out.add(p);
            }
        }
        return out;
    }

    private static void validateOneStatement(
            String one,
            Map<String, TableSchema> tableIndex,
            Locale ui,
            List<SemanticError> findings) {
        SchemaEntityLabels labels = SchemaEntityLabels.CASSANDRA;
        Set<String> reportedEntities = new HashSet<>();
        Set<String> reportedColumns = new HashSet<>();
        Set<String> reportedTypes = new HashSet<>();
        try {
            List<CqlToken> tokens = new CqlLexer(one).tokenize();
            if (indexOfKeyword(tokens, "TRUNCATE") >= 0) {
                String table = extractTruncateTable(tokens);
                if (table != null) {
                    SchemaValidationSupport.reportMissingEntity(
                            table, tableIndex, reportedEntities, findings, ui, labels);
                }
                return;
            }
            String table = resolveTable(tokens);
            if (table != null) {
                SchemaValidationSupport.reportMissingEntity(
                        table, tableIndex, reportedEntities, findings, ui, labels);
            }
            if (table == null || !SchemaValidationSupport.tableExists(table, tableIndex)) {
                return;
            }
            TableSchema ts = tableIndex.get(SchemaValidationSupport.normalizeKey(
                    SchemaValidationSupport.bareEntityName(table)));
            for (String col : extractColumnRefs(tokens)) {
                SchemaValidationSupport.reportMissingField(
                        col, table, tableIndex, reportedColumns, findings, ui, labels);
                checkWhereLiteralMismatch(col, tokens, ts, table, reportedTypes, findings, ui);
                checkInsertLiteralMismatch(col, tokens, ts, table, reportedTypes, findings, ui);
            }
        } catch (CqlLexer.LexException ignored) {
        }
    }

    private static String extractTruncateTable(List<CqlToken> t) {
        int tr = indexOfKeyword(t, "TRUNCATE");
        if (tr < 0) {
            return null;
        }
        int j = skipQualifiedName(t, tr + 1);
        return j < 0 ? null : qualifiedNameBefore(t, j);
    }

    private static String resolveTable(List<CqlToken> t) {
        if (indexOfKeyword(t, "SELECT") >= 0) {
            return extractFromTable(t);
        }
        if (indexOfKeyword(t, "INSERT") >= 0) {
            return extractInsertTable(t);
        }
        if (indexOfKeyword(t, "UPDATE") >= 0) {
            return extractUpdateDeleteTable(t, "UPDATE");
        }
        if (indexOfKeyword(t, "DELETE") >= 0) {
            return extractUpdateDeleteTable(t, "DELETE");
        }
        return null;
    }

    private static Set<String> extractColumnRefs(List<CqlToken> t) {
        Set<String> cols = new HashSet<>();
        int select = indexOfKeyword(t, "SELECT");
        int from = indexOfKeyword(t, "FROM");
        if (select >= 0 && from > select) {
            collectIdentifiers(t, select + 1, from, cols);
        }
        int where = indexOfKeyword(t, "WHERE");
        if (where >= 0) {
            int end = indexOfKeyword(t, "ALLOW");
            if (end < 0) {
                end = indexOfKeyword(t, "LIMIT");
            }
            if (end < 0) {
                end = indexOfKeyword(t, "ORDER");
            }
            if (end < 0) {
                end = t.size();
            }
            collectIdentifiers(t, where + 1, end, cols);
        }
        int set = indexOfKeyword(t, "SET");
        if (set >= 0) {
            collectSetColumns(t, set + 1, cols);
        }
        int insert = indexOfKeyword(t, "INSERT");
        if (insert >= 0) {
            int lp = findParenAfterTable(t, insert);
            if (lp >= 0) {
                int rp = findMatchingParen(t, lp);
                if (rp > lp) {
                    collectIdentifiers(t, lp + 1, rp, cols);
                }
            }
        }
        cols.removeIf(c -> CQL_KEYWORDS.contains(c.toUpperCase(Locale.ROOT)));
        return cols;
    }

    private static void checkInsertLiteralMismatch(
            String col,
            List<CqlToken> t,
            TableSchema ts,
            String table,
            Set<String> reportedTypes,
            List<SemanticError> findings,
            Locale ui) {
        int values = indexOfKeyword(t, "VALUES");
        if (values < 0) {
            return;
        }
        ColumnSchema cs = SchemaValidationSupport.findColumn(ts, col);
        if (cs == null || cs.getDataType() == null) {
            return;
        }
        SchemaValidationSupport.TypeFamily colFamily =
                SchemaValidationSupport.typeFamilyFromSqlType(cs.getDataType());
        // Heuristic: column list before VALUES matched positionally is complex; skip deep insert check
    }

    private static void collectSetColumns(List<CqlToken> t, int start, Set<String> cols) {
        int depth = 0;
        for (int i = start; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (tok.type() == CqlTokenType.RIGHT_PAREN) {
                depth = Math.max(0, depth - 1);
            }
            if (depth == 0 && tok.type() == CqlTokenType.OPERATOR && "=".equals(tok.value())) {
                if (i > start && isNameToken(t.get(i - 1))) {
                    cols.add(SchemaValidationSupport.fieldPart(t.get(i - 1).value()));
                }
            }
        }
    }

    private static void collectIdentifiers(List<CqlToken> t, int start, int end, Set<String> cols) {
        for (int i = start; i < end && i < t.size(); i++) {
            if (isNameToken(t.get(i))) {
                String v = t.get(i).value();
                if (!"?".equals(v) && !CQL_KEYWORDS.contains(v.toUpperCase(Locale.ROOT))) {
                    cols.add(SchemaValidationSupport.fieldPart(v));
                }
            }
        }
    }

    private static void checkWhereLiteralMismatch(
            String col,
            List<CqlToken> t,
            TableSchema ts,
            String table,
            Set<String> reportedTypes,
            List<SemanticError> findings,
            Locale ui) {
        ColumnSchema cs = SchemaValidationSupport.findColumn(ts, col);
        if (cs == null || cs.getDataType() == null) {
            return;
        }
        SchemaValidationSupport.TypeFamily colFamily =
                SchemaValidationSupport.typeFamilyFromSqlType(cs.getDataType());
        int where = indexOfKeyword(t, "WHERE");
        if (where < 0) {
            return;
        }
        for (int i = where + 1; i + 2 < t.size(); i++) {
            if (!col.equalsIgnoreCase(SchemaValidationSupport.fieldPart(t.get(i).value()))
                    || t.get(i + 1).type() != CqlTokenType.OPERATOR) {
                continue;
            }
            String op = t.get(i + 1).value();
            if (!"=".equals(op) && !"<".equals(op) && !">".equals(op) && !"<=".equals(op) && !">=".equals(op)) {
                continue;
            }
            CqlToken lit = t.get(i + 2);
            SchemaValidationSupport.TypeFamily litFamily = switch (lit.type()) {
                case STRING -> SchemaValidationSupport.TypeFamily.STRING;
                case NUMBER -> SchemaValidationSupport.typeFamilyFromLiteral(lit.value());
                default -> SchemaValidationSupport.TypeFamily.UNKNOWN;
            };
            if (litFamily != SchemaValidationSupport.TypeFamily.UNKNOWN && litFamily != colFamily) {
                SchemaValidationSupport.reportTypeMismatch(col, colFamily, litFamily, reportedTypes, findings, ui);
            }
        }
    }

    private static String extractFromTable(List<CqlToken> t) {
        int f = indexOfKeyword(t, "FROM");
        if (f < 0) {
            return null;
        }
        int j = skipQualifiedName(t, f + 1);
        return j < 0 ? null : qualifiedNameBefore(t, j);
    }

    private static String extractInsertTable(List<CqlToken> t) {
        int ins = indexOfKeyword(t, "INSERT");
        if (ins < 0 || !keywordSequenceAt(t, ins, "INSERT", "INTO")) {
            return null;
        }
        int j = skipQualifiedName(t, ins + 2);
        return j < 0 ? null : qualifiedNameBefore(t, j);
    }

    private static String extractUpdateDeleteTable(List<CqlToken> t, String kind) {
        if ("UPDATE".equals(kind)) {
            int u = indexOfKeyword(t, "UPDATE");
            if (u < 0) {
                return null;
            }
            int j = skipQualifiedName(t, u + 1);
            return j < 0 ? null : qualifiedNameBefore(t, j);
        }
        int f = indexOfKeyword(t, "FROM");
        if (f < 0) {
            return null;
        }
        int j = skipQualifiedName(t, f + 1);
        return j < 0 ? null : qualifiedNameBefore(t, j);
    }

    private static int findParenAfterTable(List<CqlToken> t, int insertIdx) {
        int j = skipQualifiedName(t, insertIdx + 2);
        if (j < 0) {
            return -1;
        }
        for (int i = j; i < t.size(); i++) {
            if (t.get(i).type() == CqlTokenType.LEFT_PAREN) {
                return i;
            }
        }
        return -1;
    }

    private static int findMatchingParen(List<CqlToken> t, int open) {
        int depth = 0;
        for (int i = open; i < t.size(); i++) {
            if (t.get(i).type() == CqlTokenType.LEFT_PAREN) {
                depth++;
            } else if (t.get(i).type() == CqlTokenType.RIGHT_PAREN) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String qualifiedNameBefore(List<CqlToken> t, int afterIdx) {
        int end = afterIdx - 1;
        if (end < 0) {
            return null;
        }
        int start = end;
        while (start - 2 >= 0 && t.get(start - 1).type() == CqlTokenType.DOT) {
            start -= 2;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = start; k <= end; k++) {
            if (k > start) {
                sb.append('.');
            }
            sb.append(t.get(k).value());
        }
        return sb.toString();
    }

    private static int skipQualifiedName(List<CqlToken> t, int j) {
        if (j < 0 || j >= t.size() || !isNameToken(t.get(j))) {
            return -1;
        }
        j++;
        while (j < t.size() && t.get(j).type() == CqlTokenType.DOT) {
            j++;
            if (j >= t.size() || !isNameToken(t.get(j))) {
                return -1;
            }
            j++;
        }
        return j;
    }

    private static boolean keywordSequenceAt(List<CqlToken> tok, int from, String... words) {
        int j = from;
        for (String w : words) {
            if (j >= tok.size()) {
                return false;
            }
            CqlToken t = tok.get(j);
            if (t.type() != CqlTokenType.KEYWORD || !w.equalsIgnoreCase(t.value())) {
                return false;
            }
            j++;
        }
        return true;
    }

    private static int indexOfKeyword(List<CqlToken> t, String word) {
        for (int i = 0; i < t.size(); i++) {
            CqlToken tok = t.get(i);
            if (tok.type() == CqlTokenType.EOF) {
                break;
            }
            if (tok.type() == CqlTokenType.KEYWORD && word.equalsIgnoreCase(tok.value())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isNameToken(CqlToken tok) {
        return tok.type() == CqlTokenType.IDENTIFIER || tok.type() == CqlTokenType.UUID_LITERAL;
    }
}
