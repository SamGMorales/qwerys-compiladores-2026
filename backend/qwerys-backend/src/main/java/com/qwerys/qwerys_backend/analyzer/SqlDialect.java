package com.qwerys.qwerys_backend.analyzer;

/**
 * SQL dialects supported by the Qwerys analyzer pipeline.
 * Use GENERIC for standard ANSI SQL when no dialect-specific features are needed.
 */
public enum SqlDialect {
    GENERIC,
    MYSQL,
    POSTGRESQL,
    SQLITE,
    SQLSERVER,
    ORACLE
}
