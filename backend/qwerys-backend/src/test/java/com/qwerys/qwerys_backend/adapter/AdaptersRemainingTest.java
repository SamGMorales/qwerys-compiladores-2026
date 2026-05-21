package com.qwerys.qwerys_backend.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Day 28 — SQLite, SQL Server, Oracle JDBC adapters (no live servers required).
 */
class AdaptersRemainingTest {

    @Test
    void getDbType_strings() {
        assertEquals("sqlite", new SQLiteAdapter().getDbType());
        assertEquals("sqlserver", new SqlServerAdapter().getDbType());
        assertEquals("oracle", new OracleAdapter().getDbType());
    }

    @Test
    void testConnection_deadOrMissing_returnsFalseQuietly() {
        DatabaseConfig sqlServer =
                new DatabaseConfig("localhost", 9999, "master", "u", "p", "sqlserver", 2);
        DatabaseConfig oracle =
                new DatabaseConfig("localhost", 9999, "ORCL", "u", "p", "oracle", 2);
        DatabaseConfig sqliteBad =
                new DatabaseConfig("localhost", 9999, "/ruta/inexistente/x.db", null, null, "sqlite", 2);

        assertDoesNotThrow(
                () -> {
                    assertFalse(new SqlServerAdapter().testConnection(sqlServer));
                    assertFalse(new OracleAdapter().testConnection(oracle));
                    assertFalse(new SQLiteAdapter().testConnection(sqliteBad));
                });
    }

    @Test
    void factory_returnsConcreteAdapters_withoutStubBehavior() {
        assertDoesNotThrow(
                () -> {
                    DatabaseAdapter sqlite = DatabaseAdapterFactory.getAdapter("sqlite");
                    DatabaseAdapter ss = DatabaseAdapterFactory.getAdapter("sqlserver");
                    DatabaseAdapter ora = DatabaseAdapterFactory.getAdapter("oracle");
                    assertNotNull(sqlite);
                    assertNotNull(ss);
                    assertNotNull(ora);
                    assertInstanceOf(SQLiteAdapter.class, sqlite);
                    assertInstanceOf(SqlServerAdapter.class, ss);
                    assertInstanceOf(OracleAdapter.class, ora);
                    DatabaseConfig dead =
                            new DatabaseConfig("localhost", 9999, "master", null, null, "x", 2);
                    assertFalse(ss.testConnection(dead));
                });
    }

    @Test
    void oracle_executeQuery_rejectsLimitBeforeConnecting() {
        DatabaseConfig cfg = new DatabaseConfig("localhost", 9999, "ORCL", "u", "p", "oracle", 2);
        QueryExecutionResult r = new OracleAdapter().executeQuery(cfg, "SELECT 1 FROM dual LIMIT 1");
        assertFalse(r.isSuccess());
        assertEquals(
                "Oracle uses ROWNUM or FETCH FIRST instead of LIMIT",
                r.getErrorMessage());
    }
}
