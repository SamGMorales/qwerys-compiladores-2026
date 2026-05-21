package com.qwerys.qwerys_backend.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits a raw SQL string containing multiple statements separated by semicolons
 * into individual statement strings, correctly ignoring semicolons that appear
 * inside string literals, double-quoted identifiers, line comments ({@code --}, or
 * {@code #} when {@link SqlDialect#MYSQL}), or block comments.
 *
 * <p>MySQL client scripts: honors {@code DELIMITER newdelim} / {@code DELIMITER ;} so the
 * active terminator switches (e.g. to {@code //} for procedure bodies) until reset.
 */
public final class StatementSplitter {

    /**
     * Inline scripts: {@code DELIMITER // CREATE ...} on one line — insert newline after the
     * delimiter so the directive matches mysql client semantics (“rest of line” for DELIMITER).
     */
    private static final Pattern DELIMITER_BEFORE_STATEMENT = Pattern.compile(
            "(\\bDELIMITER\\s+\\S+)\\s+(?=(CREATE|SELECT|INSERT|UPDATE|DELETE|DROP|ALTER|SHOW|REPLACE|DESCRIBE|WITH|GRANT|REVOKE|DELIMITER)\\b)",
            Pattern.CASE_INSENSITIVE);

    private StatementSplitter() {}

    /**
     * Splits {@code rawSql} on dynamic delimiter boundaries (default {@code ;}) while respecting:
     * <ul>
     *   <li>Single-quoted string literals  {@code '...'} (with {@code ''} escape)</li>
     *   <li>Double-quoted identifiers       {@code "..."} (with {@code ""} escape)</li>
     *   <li>Line comments                   {@code -- ... \n}</li>
     *   <li>MySQL line comments            {@code # ... \n} (only when dialect is {@link SqlDialect#MYSQL})</li>
     *   <li>Block comments                  (slash-star … star-slash)</li>
     *   <li>MySQL versioned comments         (slash-star-bang …) — inner SQL participates in splitting (MySQL dialect)</li>
     *   <li>MySQL {@code DELIMITER} lines — changes the terminator until the next {@code DELIMITER}</li>
     * </ul>
     *
     * <p>The trailing delimiter is consumed but NOT included in the returned strings.
     * Empty entries (after trimming) are omitted.
     *
     * @see #split(String, SqlDialect)
     */
    public static List<String> split(String rawSql) {
        return split(rawSql, SqlDialect.GENERIC);
    }

    /**
     * Same as {@link #split(String)} but respects dialect-specific comment rules (e.g. {@code #}
     * line comments for {@link SqlDialect#MYSQL} only, so PostgreSQL {@code #} bitwise XOR is unchanged).
     */
    public static List<String> split(String rawSql, SqlDialect dialect) {
        if (dialect == null) {
            dialect = SqlDialect.GENERIC;
        }
        if (rawSql == null || rawSql.isBlank()) return new ArrayList<>();
        if (dialect == SqlDialect.MYSQL) {
            rawSql = MysqlExecutableCommentSupport.expandExecutableComments(rawSql);
        }
        if (rawSql.regionMatches(true, indexFirstNonWhitespace(rawSql), "DELIMITER", 0, 9)
                || rawSql.toUpperCase().contains("DELIMITER")) {
            rawSql = DELIMITER_BEFORE_STATEMENT.matcher(rawSql).replaceAll("$1\n");
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        int len = rawSql.length();
        int blockDepth = 0;
        int proceduralDepth = 0;
        boolean lastWasWhile = false;
        boolean declarativeMode = false;
        boolean suppressSplitDeclareLead = false;
        boolean routineHeaderMode = false;
        String activeDelimiter = ";";

        while (i < len) {
            char c = rawSql.charAt(i);

            // Stand-alone DELIMITER directive line (often without trailing ';')
            int lineStmtStart = skipWhitespace(rawSql, i, len);
            if (blockDepth == 0 && proceduralDepth == 0 && !suppressSplitDeclareLead
                    && current.toString().trim().isEmpty()
                    && isDelimiterDirectiveAt(rawSql, lineStmtStart, len)) {
                int lineEnd = rawSql.indexOf('\n', lineStmtStart);
                if (lineEnd < 0) {
                    lineEnd = len;
                }
                String line = rawSql.substring(lineStmtStart, lineEnd).trim();
                if (!line.isEmpty()) {
                    statements.add(line);
                }
                String nd = newDelimiterFromDirectiveLine(line);
                if (nd != null) {
                    activeDelimiter = nd.isEmpty() ? ";" : nd;
                }
                i = lineEnd < len ? lineEnd + 1 : len;
                continue;
            }

            if (c == '\'') {
                current.append(c);
                i++;
                while (i < len) {
                    char sc = rawSql.charAt(i);
                    current.append(sc);
                    i++;
                    if (sc == '\'' && (i >= len || rawSql.charAt(i) != '\'')) break;
                    if (sc == '\'' && i < len && rawSql.charAt(i) == '\'') {
                        current.append(rawSql.charAt(i++));
                    }
                }
                continue;
            }

            if (c == '"') {
                current.append(c);
                i++;
                while (i < len) {
                    char sc = rawSql.charAt(i);
                    current.append(sc);
                    i++;
                    if (sc == '"') break;
                }
                continue;
            }

            if (c == '$' && i + 1 < len) {
                int j = i + 1;
                while (j < len && rawSql.charAt(j) != '$') {
                    char ch = rawSql.charAt(j);
                    if (!Character.isLetterOrDigit(ch) && ch != '_') {
                        break;
                    }
                    j++;
                }
                if (j < len && rawSql.charAt(j) == '$') {
                    String tag = rawSql.substring(i + 1, j);
                    String closing = "$" + tag + "$";
                    int k = j + 1;
                    current.append(rawSql, i, k);
                    i = k;
                    while (i < len) {
                        if (rawSql.startsWith(closing, i)) {
                            current.append(closing);
                            i += closing.length();
                            break;
                        }
                        current.append(rawSql.charAt(i++));
                    }
                    continue;
                }
            }

            if (c == '-' && i + 1 < len && rawSql.charAt(i + 1) == '-') {
                while (i < len && rawSql.charAt(i) != '\n') {
                    current.append(rawSql.charAt(i++));
                }
                continue;
            }

            if (dialect == SqlDialect.MYSQL && c == '#') {
                while (i < len && rawSql.charAt(i) != '\n') {
                    current.append(rawSql.charAt(i++));
                }
                continue;
            }

            if (c == '/' && i + 1 < len && rawSql.charAt(i + 1) == '*') {
                current.append(c);
                current.append(rawSql.charAt(i + 1));
                i += 2;
                while (i + 1 < len && !(rawSql.charAt(i) == '*' && rawSql.charAt(i + 1) == '/')) {
                    current.append(rawSql.charAt(i++));
                }
                if (i + 1 < len) {
                    current.append(rawSql.charAt(i++));
                    current.append(rawSql.charAt(i++));
                }
                continue;
            }

            if (Character.isLetter(c)) {
                int wordStart = i;
                StringBuilder word = new StringBuilder();
                while (i < len && Character.isLetterOrDigit(rawSql.charAt(i))) {
                    word.append(rawSql.charAt(i++));
                }
                String keyword = word.toString().toUpperCase();
                if (keyword.equals("DECLARE")) {
                    declarativeMode = true;
                    suppressSplitDeclareLead = true;
                } else if (keyword.equals("BEGIN")) {
                    int j = i;
                    while (j < len && rawSql.charAt(j) == ' ') j++;
                    String nextChar = (j < len) ? String.valueOf(rawSql.charAt(j)) : "";
                    String nextWord = "";
                    int k = j;
                    while (k < len && Character.isLetter(rawSql.charAt(k))) {
                        nextWord += rawSql.charAt(k++);
                    }
                    boolean isTcl = nextWord.toUpperCase().startsWith("TRAN") || nextChar.equals(";");
                    if (declarativeMode) {
                        declarativeMode = false;
                        suppressSplitDeclareLead = false;
                    }
                    if (!isTcl) {
                        blockDepth++;
                    }
                    routineHeaderMode = false;
                } else if (keyword.equals("IS") || keyword.equals("AS")) {
                    if (blockDepth == 0 && proceduralDepth == 0) {
                        String bufferSoFar = current.toString().toUpperCase(Locale.ROOT);
                        if (bufferSoFar.contains("PROCEDURE") || bufferSoFar.contains("FUNCTION")
                                || bufferSoFar.contains("TRIGGER")) {
                            // Cassandra / CQL UDF: AS 'java or js body'; — semicolon ends the statement, not header.
                            if (!asFollowedByQuotedExternalBody(rawSql, i, len)) {
                                routineHeaderMode = true;
                            }
                        }
                    }
                } else if (keyword.equals("WHILE")) {
                    lastWasWhile = true;
                    proceduralDepth++;
                } else if (keyword.equals("FOR")) {
                    lastWasWhile = false;
                    proceduralDepth++;
                } else if (keyword.equals("IF")) {
                    lastWasWhile = false;
                    proceduralDepth++;
                } else if (keyword.equals("CASE")) {
                    lastWasWhile = false;
                    proceduralDepth++;
                } else if (keyword.equals("LOOP")) {
                    if (lastWasWhile) {
                        lastWasWhile = false;
                    } else {
                        proceduralDepth++;
                    }
                } else if (keyword.equals("REPEAT")) {
                    lastWasWhile = false;
                    proceduralDepth++;
                } else if (keyword.equals("DO")) {
                    lastWasWhile = false;
                } else if (keyword.equals("END")) {
                    int j = i;
                    while (j < len && Character.isWhitespace(rawSql.charAt(j))) {
                        j++;
                    }
                    StringBuilder followerWord = new StringBuilder();
                    while (j < len && Character.isLetter(rawSql.charAt(j))) {
                        followerWord.append(rawSql.charAt(j++));
                    }
                    String follower = followerWord.toString().toUpperCase();
                    if ("IF".equals(follower) || "LOOP".equals(follower) || "WHILE".equals(follower)
                            || "CASE".equals(follower) || "REPEAT".equals(follower)) {
                        if (proceduralDepth > 0) {
                            proceduralDepth--;
                        }
                        current.append(rawSql, wordStart, j);
                        i = j;
                        declarativeMode = false;
                        continue;
                    }
                    if (blockDepth > 0) {
                        blockDepth--;
                    }
                    declarativeMode = false;
                    current.append(rawSql, wordStart, i);
                    continue;
                }
                current.append(rawSql, wordStart, i);
                continue;
            }

            String delim = activeDelimiter;
            if (blockDepth == 0 && proceduralDepth == 0 && !suppressSplitDeclareLead
                    && !routineHeaderMode
                    && delimiterMatchesAt(rawSql, i, len, delim)) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                    String nd = newDelimiterFromDirectiveLine(stmt);
                    if (nd != null) {
                        activeDelimiter = nd.isEmpty() ? ";" : nd;
                    }
                }
                i += delim.length();
                current.setLength(0);
                routineHeaderMode = false;
                continue;
            }

            current.append(c);
            i++;
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) statements.add(last);

        return statements;
    }

    private static int indexFirstNonWhitespace(String raw) {
        int i = 0;
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipWhitespace(String raw, int i, int len) {
        while (i < len && Character.isWhitespace(raw.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * {@code CREATE ... FUNCTION ... AS 'one-line body'} (Cassandra UDF, etc.) must still split on the
     * trailing delimiter; {@link #routineHeaderMode} is only for procedural headers like {@code AS BEGIN}.
     */
    private static boolean asFollowedByQuotedExternalBody(String raw, int afterAsWord, int len) {
        int j = skipWhitespace(raw, afterAsWord, len);
        return j < len && (raw.charAt(j) == '\'' || raw.charAt(j) == '"');
    }

    private static boolean isDelimiterDirectiveAt(String raw, int pos, int len) {
        if (pos + 9 > len) {
            return false;
        }
        if (!raw.regionMatches(true, pos, "DELIMITER", 0, 9)) {
            return false;
        }
        int afterKeyword = pos + 9;
        if (afterKeyword >= len || !Character.isWhitespace(raw.charAt(afterKeyword))) {
            return false;
        }
        return true;
    }

    /**
     * If {@code line} is a {@code DELIMITER} directive, returns the new delimiter string;
     * otherwise {@code null}.
     */
    private static String newDelimiterFromDirectiveLine(String line) {
        String t = line.trim();
        if (t.length() < 9 || !t.regionMatches(true, 0, "DELIMITER", 0, 9)) {
            return null;
        }
        return t.substring(9).trim();
    }

    private static boolean delimiterMatchesAt(String raw, int i, int len, String delim) {
        if (delim == null || delim.isEmpty()) {
            delim = ";";
        }
        if (i < 0 || i + delim.length() > len) {
            return false;
        }
        return raw.regionMatches(false, i, delim, 0, delim.length());
    }
}
