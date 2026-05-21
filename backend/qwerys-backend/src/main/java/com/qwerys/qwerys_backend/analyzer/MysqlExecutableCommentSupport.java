package com.qwerys.qwerys_backend.analyzer;

/**
 * MySQL / MariaDB versioned block comments: slash-star-bang … closing star-slash.
 * <p>The inner text is parsed as SQL on the server when the version matches; for static analysis
 * we always treat that region as real SQL. Ordinary block comments (without bang) stay comments.</p>
 */
final class MysqlExecutableCommentSupport {

    private MysqlExecutableCommentSupport() {}

    /**
     * {@code start} = index of first character <em>inside</em> the block after the optional version digits
     * (i.e. first character of the conditional SQL payload).
     *
     * @return index of {@code *} that begins the closing {@code *\/}, or {@code -1} if none
     */
    static int indexOfExecutableCommentPayloadEnd(String sql, int start) {
        if (sql == null || start < 0 || start > sql.length()) {
            return -1;
        }
        int i = start;
        int len = sql.length();
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                i = skipSingleQuoted(sql, i, len);
                continue;
            }
            if (c == '"') {
                i = skipDoubleQuoted(sql, i, len);
                continue;
            }
            if (c == '`') {
                i = skipBacktickQuoted(sql, i, len);
                continue;
            }
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipNestedBlockComment(sql, i + 2, len);
                continue;
            }
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                while (i < len && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '#') {
                while (i < len && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '*' && i + 1 < len && sql.charAt(i + 1) == '/') {
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * Replaces every {@code /*! … *\/} region with its inner SQL payload so statement splitting
     * sees real semicolons. Does not alter plain block comments.
     */
    static String expandExecutableComments(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int len = sql.length();
        while (i < len) {
            if (i + 2 < len
                    && sql.charAt(i) == '/'
                    && sql.charAt(i + 1) == '*'
                    && sql.charAt(i + 2) == '!') {
                int j = i + 3;
                while (j < len && Character.isDigit(sql.charAt(j))) {
                    j++;
                }
                int innerStart = j;
                int endStar = indexOfExecutableCommentPayloadEnd(sql, innerStart);
                if (endStar < 0) {
                    out.append(sql.charAt(i++));
                    continue;
                }
                out.append(sql, innerStart, endStar);
                i = endStar + 2;
            } else {
                out.append(sql.charAt(i++));
            }
        }
        return out.toString();
    }

    private static int skipSingleQuoted(String sql, int i, int len) {
        i++;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipDoubleQuoted(String sql, int i, int len) {
        i++;
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '"') {
                if (i + 1 < len && sql.charAt(i + 1) == '"') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipBacktickQuoted(String sql, int i, int len) {
        i++;
        while (i < len) {
            if (sql.charAt(i) == '`') {
                if (i + 1 < len && sql.charAt(i + 1) == '`') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /** After the opening {@code *} of {@code /*} — {@code i} is first char inside the nested block. */
    private static int skipNestedBlockComment(String sql, int i, int len) {
        while (i + 1 < len) {
            if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return len;
    }
}
