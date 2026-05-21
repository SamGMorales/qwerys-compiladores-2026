package com.qwerys.qwerys_backend.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ensures the system prompt hint sent to the LLM is dialect-specific for every supported
 * SQL engine, not the generic "Use SQL syntax appropriate for the declared engine family."
 * Without these hints the model defaults to MySQL-style suggestions, which is the bias
 * we want to eliminate across MySQL, PostgreSQL, Oracle, SQL Server and SQLite.
 */
class AiLocaleHelperEngineHintTest {

    private static final String GENERIC_DEFAULT = "Use SQL syntax appropriate for the declared engine family.";

    @Test
    void mysqlHintMentionsBackticksAndLimit() {
        String hint = AiLocaleHelper.engineSyntaxHint("mysql");
        assertFalse(hint.equals(GENERIC_DEFAULT), "MySQL should not fall through to default");
        assertTrue(hint.contains("MySQL"));
        assertTrue(hint.contains("LIMIT"));
        assertTrue(hint.contains("backticks"));
    }

    @Test
    void postgresqlHintMentionsJsonbAndIlike() {
        String hint = AiLocaleHelper.engineSyntaxHint("postgresql");
        assertFalse(hint.equals(GENERIC_DEFAULT));
        assertTrue(hint.contains("PostgreSQL"));
        assertTrue(hint.contains("JSONB"));
        assertTrue(hint.contains("ILIKE"));
    }

    @Test
    void oracleHintMentionsFetchFirstAndDual() {
        String hint = AiLocaleHelper.engineSyntaxHint("oracle");
        assertFalse(hint.equals(GENERIC_DEFAULT));
        assertTrue(hint.contains("Oracle"));
        assertTrue(hint.contains("FETCH FIRST"));
        assertTrue(hint.contains("DUAL"));
    }

    @Test
    void sqlServerHintMentionsTopAndBrackets() {
        String hint = AiLocaleHelper.engineSyntaxHint("sqlserver");
        assertFalse(hint.equals(GENERIC_DEFAULT));
        assertTrue(hint.contains("T-SQL"));
        assertTrue(hint.contains("TOP"));
        assertTrue(hint.contains("[Bracketed]"));
    }

    @Test
    void sqliteHintWarnsAboutUnsupportedJoins() {
        String hint = AiLocaleHelper.engineSyntaxHint("sqlite");
        assertFalse(hint.equals(GENERIC_DEFAULT));
        assertTrue(hint.contains("SQLite"));
        assertTrue(hint.contains("no RIGHT JOIN"));
        assertTrue(hint.contains("no FULL OUTER JOIN"));
    }

    @Test
    void caseInsensitiveLookup() {
        assertTrue(AiLocaleHelper.engineSyntaxHint("ORACLE").contains("Oracle"));
        assertTrue(AiLocaleHelper.engineSyntaxHint("SqlServer").contains("T-SQL"));
        assertTrue(AiLocaleHelper.engineSyntaxHint("SQLite").contains("SQLite"));
    }

    @Test
    void noSqlHintsStillPresent() {
        assertTrue(AiLocaleHelper.engineSyntaxHint("mongodb").contains("MongoDB"));
        assertTrue(AiLocaleHelper.engineSyntaxHint("dynamodb").contains("DynamoDB"));
    }
}
