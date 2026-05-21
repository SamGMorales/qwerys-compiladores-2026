package com.qwerys.qwerys_backend.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MySQLAdapterTest {

    @Test
    void testConnection_unreachablePort_returnsFalse() {
        DatabaseConfig cfg =
                new DatabaseConfig("localhost", 9999, "test", "u", "p", "mysql", 2);
        MySQLAdapter adapter = new MySQLAdapter();
        assertFalse(adapter.testConnection(cfg));
    }

    @Test
    void getDbType_returnsMysql() {
        assertEquals("mysql", new MySQLAdapter().getDbType());
    }

    @Test
    void executeQuery_emptyStatement_returnsFailureWithoutLiveDb() {
        DatabaseConfig cfg =
                new DatabaseConfig("localhost", 9999, "test", "u", "p", "mysql", 2);
        MySQLAdapter adapter = new MySQLAdapter();
        QueryExecutionResult r = adapter.executeQuery(cfg, "   ");
        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
        assertEquals("Empty query", r.getErrorMessage());
        assertNotNull(r.getRows());
        assertEquals(0, r.getAffectedRows());
    }

    @Test
    void factory_returnsMySqlAdapterInstance() {
        DatabaseAdapter a = DatabaseAdapterFactory.getAdapter("mysql");
        assertInstanceOf(MySQLAdapter.class, a);
    }
}
