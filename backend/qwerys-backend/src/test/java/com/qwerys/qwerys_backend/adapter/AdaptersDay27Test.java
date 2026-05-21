package com.qwerys.qwerys_backend.adapter;

import com.qwerys.qwerys_backend.exception.UnsupportedDatabaseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Day 27 — real adapter wiring (no live servers required).
 */
class AdaptersDay27Test {

    private static DatabaseConfig deadPortConfig() {
        return new DatabaseConfig("localhost", 9999, "test", null, null, "x", 2);
    }

    @Test
    void getDbType_strings() {
        assertEquals("postgresql", new PostgreSQLAdapter().getDbType());
        assertEquals("mongodb", new MongoDBAdapter().getDbType());
        assertEquals("redis", new RedisAdapter().getDbType());
        assertEquals("cassandra", new CassandraAdapter().getDbType());
        assertEquals("dynamodb", new DynamoDBAdapter().getDbType());
        assertEquals("elasticsearch", new ElasticsearchAdapter().getDbType());
    }

    @Test
    void testConnection_deadPort_returnsFalseQuietly() {
        DatabaseConfig cfg = deadPortConfig();
        assertDoesNotThrow(
                () -> {
                    assertFalse(new PostgreSQLAdapter().testConnection(cfg));
                    assertFalse(new MongoDBAdapter().testConnection(cfg));
                    assertFalse(new RedisAdapter().testConnection(cfg));
                    assertFalse(new CassandraAdapter().testConnection(cfg));
                    assertFalse(new DynamoDBAdapter().testConnection(cfg));
                    assertFalse(new ElasticsearchAdapter().testConnection(cfg));
                });
    }

    @Test
    void factory_returnsConcreteAdapters() {
        assertInstanceOf(PostgreSQLAdapter.class, DatabaseAdapterFactory.getAdapter("postgresql"));
        assertInstanceOf(MongoDBAdapter.class, DatabaseAdapterFactory.getAdapter("mongodb"));
        assertInstanceOf(RedisAdapter.class, DatabaseAdapterFactory.getAdapter("redis"));
        assertInstanceOf(CassandraAdapter.class, DatabaseAdapterFactory.getAdapter("cassandra"));
        assertInstanceOf(DynamoDBAdapter.class, DatabaseAdapterFactory.getAdapter("dynamodb"));
        assertInstanceOf(ElasticsearchAdapter.class, DatabaseAdapterFactory.getAdapter("elasticsearch"));
    }

    @Test
    void factory_still_resolves_sqlite_sqlserver_oracle() {
        assertDoesNotThrow(
                () -> {
                    assertNotNull(DatabaseAdapterFactory.getAdapter("sqlite"));
                    assertNotNull(DatabaseAdapterFactory.getAdapter("sqlserver"));
                    assertNotNull(DatabaseAdapterFactory.getAdapter("oracle"));
                });
    }

    @Test
    void factory_unknown_stillThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedDatabaseException.class, () -> DatabaseAdapterFactory.getAdapter("not-a-real-engine"));
    }
}
