package com.qwerys.qwerys_backend.model.dto;

import com.qwerys.qwerys_backend.adapter.DatabaseConfig;

/**
 * Payload for {@code POST /api/schema/describe}: connection plus a table (or collection) name.
 */
public record SchemaDescribeRequest(DatabaseConfig connection, String tableName) {}
