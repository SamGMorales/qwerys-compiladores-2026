package com.qwerys.qwerys_backend.adapter;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Connection parameters for a database engine. Immutable value object.
 * {@code port} and {@code connectionTimeoutSeconds} use {@link Integer} so JSON may omit them or send {@code null}
 * without failing deserialization; defaults are applied in the compact constructor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DatabaseConfig(
        String host,
        Integer port,
        String database,
        String username,
        String password,
        @JsonAlias({"db_type", "engine", "engineType"}) String dbType,
        Integer connectionTimeoutSeconds) {

    public DatabaseConfig {
        if (host == null || host.isBlank()) {
            host = "localhost";
        }
        if (port == null) {
            port = 0;
        }
        if (connectionTimeoutSeconds == null || connectionTimeoutSeconds <= 0) {
            connectionTimeoutSeconds = 30;
        }
    }
}
