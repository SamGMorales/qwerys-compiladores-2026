package com.qwerys.qwerys_backend.ai;

import java.util.Locale;

public final class AiLocaleHelper {

    private AiLocaleHelper() {}

    public static Locale resolve(String localeTag) {
        if (localeTag == null || localeTag.isBlank()) {
            return Locale.forLanguageTag("es");
        }
        return Locale.forLanguageTag(localeTag.strip());
    }

    public static boolean isSpanish(Locale locale) {
        return locale != null && locale.getLanguage().toLowerCase(Locale.ROOT).startsWith("es");
    }

    public static String languageInstruction(Locale locale) {
        return isSpanish(locale)
                ? "Responde TODO en español (mensajes, sugerencias, explicaciones educativas)."
                : "Respond entirely in English (messages, suggestions, educational explanations).";
    }

    public static String engineSyntaxHint(String databaseType) {
        String db = databaseType == null ? "mysql" : databaseType.toLowerCase(Locale.ROOT);
        return switch (db) {
            case "mongodb" -> "Use MongoDB query language / shell syntax as appropriate.";
            case "redis" -> "Use Redis command syntax.";
            case "cassandra" -> "Use CQL (Cassandra Query Language) syntax.";
            case "dynamodb" -> "Use DynamoDB API / PartiQL-style syntax as appropriate.";
            case "elasticsearch" -> "Use Elasticsearch Query DSL / relevant API syntax.";
            case "mysql" -> "Use MySQL 8 syntax: LIMIT n OFFSET m (NOT FETCH FIRST), backticks for identifiers, "
                    + "JSON_TABLE / JSON_EXTRACT, ON DUPLICATE KEY UPDATE, CTEs and window functions (8.0+). "
                    + "Avoid SQL Server hints (WITH (INDEX), NOLOCK), Oracle DUAL/CONNECT BY, and PartiQL/Mongo syntax.";
            case "postgresql" -> "Use PostgreSQL syntax: LIMIT/OFFSET or standard FETCH FIRST n ROWS ONLY, "
                    + "ILIKE (case-insensitive), RETURNING clause, JSONB operators (->, ->>, #>, @>), "
                    + "DISTINCT ON, LATERAL joins, generate_series. Use double-quoted identifiers if needed. "
                    + "Avoid SQL Server hints, Oracle ROWNUM/DUAL, MySQL backticks, FROM DUAL, NVL (use COALESCE).";
            case "oracle" -> "Use Oracle SQL syntax: FETCH FIRST n ROWS ONLY (NOT LIMIT), VARCHAR2, NVL/NVL2/COALESCE, "
                    + "DUAL for selectless expressions, CONNECT BY for hierarchies, ROWNUM, MERGE, (+) outer join, "
                    + "MODEL clause, PIVOT/UNPIVOT, analytic functions with KEEP. "
                    + "Avoid LIMIT, TOP, ILIKE, JSONB, MySQL backticks, SQL Server hints.";
            case "sqlserver" -> "Use T-SQL syntax: TOP n (or OFFSET … FETCH NEXT n ROWS ONLY) — NOT LIMIT. "
                    + "[Bracketed] identifiers, OUTPUT clause for INSERT/UPDATE/DELETE/MERGE, table hints WITH (NOLOCK)/(INDEX = …), "
                    + "TRY_CAST/TRY_CONVERT, STRING_AGG (2017+), JSON_VALUE/JSON_QUERY. "
                    + "Avoid LIMIT, ILIKE, JSONB, FETCH FIRST without OFFSET, MySQL backticks, Oracle DUAL/CONNECT BY.";
            case "sqlite" -> "Use SQLite syntax: LIMIT/OFFSET (standard), no RIGHT JOIN, no FULL OUTER JOIN, "
                    + "no PIVOT/UNPIVOT, no stored procedures. Use REPLACE INTO or ON CONFLICT DO …, "
                    + "JSON1 functions (json_extract, json_each), WITHOUT ROWID tables, RETURNING (3.35+), "
                    + "window functions (3.25+), CTEs. "
                    + "Avoid TOP, FETCH FIRST, WITH (INDEX), INDEXED BY hints unless intentional, NVL, DUAL.";
            default -> "Use SQL syntax appropriate for the declared engine family.";
        };
    }
}
