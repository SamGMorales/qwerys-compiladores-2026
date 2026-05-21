package com.qwerys.qwerys_backend.adapter;

import com.qwerys.qwerys_backend.exception.UnsupportedDatabaseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseAdapterTest {

    @Test
    void databaseConfig_holdsValues_andAppliesDefaults() {
        DatabaseConfig full = new DatabaseConfig(
                "db.example.com", 5432, "app", "user", "secret", "postgresql", 45);
        assertEquals("db.example.com", full.host());
        assertEquals(5432, full.port());
        assertEquals("app", full.database());
        assertEquals("user", full.username());
        assertEquals("secret", full.password());
        assertEquals("postgresql", full.dbType());
        assertEquals(45, full.connectionTimeoutSeconds());

        DatabaseConfig blankHost = new DatabaseConfig(
                "   ", 3306, "d", "u", "p", "mysql", 0);
        assertEquals("localhost", blankHost.host());
        assertEquals(30, blankHost.connectionTimeoutSeconds());
    }

    @Test
    void schemaModels_assignFields() {
        ColumnSchema col = new ColumnSchema("id", "BIGINT", false, true, null);
        assertEquals("id", col.getColumnName());
        assertEquals("BIGINT", col.getDataType());
        assertTrue(col.isPrimaryKey());
        assertFalse(col.isNullable());

        TableSchema table = new TableSchema(
                "users",
                List.of(col),
                List.of("id"),
                List.of("fk_org"));
        assertEquals("users", table.getTableName());
        assertEquals(1, table.getColumns().size());
        assertEquals("id", table.getPrimaryKeys().get(0));
        assertEquals("fk_org", table.getForeignKeys().get(0));

        DatabaseSchema schema = new DatabaseSchema("mysql", "appdb", List.of(table));
        assertEquals("mysql", schema.getDbType());
        assertEquals("appdb", schema.getDatabaseName());
        assertEquals(1, schema.getTables().size());
    }

    @Test
    void queryExecutionResult_successAndFailureShapes() {
        QueryExecutionResult ok = new QueryExecutionResult(
                true,
                List.of(Map.of("n", 1)),
                0,
                null,
                12L);
        assertTrue(ok.isSuccess());
        assertEquals(1, ok.getRows().size());
        assertEquals(1, ok.getRows().get(0).get("n"));
        assertEquals(0, ok.getAffectedRows());
        assertNull(ok.getErrorMessage());
        assertEquals(12L, ok.getExecutionTimeMs());

        QueryExecutionResult bad = new QueryExecutionResult();
        bad.setSuccess(false);
        bad.setAffectedRows(0);
        bad.setErrorMessage("boom");
        bad.setExecutionTimeMs(3L);
        assertTrue(!bad.isSuccess());
        assertEquals("boom", bad.getErrorMessage());
    }

    @Test
    void factory_returnsMysqlAdapter() {
        DatabaseAdapter a = DatabaseAdapterFactory.getAdapter("mysql");
        assertNotNull(a);
        assertEquals("mysql", a.getDbType());
    }

    @Test
    void factory_returnsPostgresqlAdapter_caseInsensitive() {
        DatabaseAdapter a = DatabaseAdapterFactory.getAdapter("PostgreSQL");
        assertNotNull(a);
        assertEquals("postgresql", a.getDbType());
    }

    @Test
    void factory_unknownEngine_throwsUnsupportedDatabaseException() {
        assertThrows(
                UnsupportedDatabaseException.class,
                () -> DatabaseAdapterFactory.getAdapter("motorfantasma"));
    }

    @Test
    void factory_customEngineId_delegatesToEmbeddedBase() {
        DatabaseAdapter a = DatabaseAdapterFactory.getAdapter("custom::CorpPg::postgresql");
        assertNotNull(a);
        assertEquals("postgresql", a.getDbType());
    }
}
