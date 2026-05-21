package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.QueryRequest;

import java.util.Locale;

/**
 * Parsed custom engine declaration ({@code custom} or {@code custom::Name::base}).
 */
public record CustomEngineContext(
        String declaredType,
        String customName,
        String referenceBase
) {
    public static boolean isCustomDeclaration(String databaseType) {
        if (databaseType == null || databaseType.isBlank()) {
            return false;
        }
        String d = databaseType.strip().toLowerCase(Locale.ROOT);
        return "custom".equals(d) || d.startsWith("custom::");
    }

    public static CustomEngineContext from(QueryRequest request) {
        return fromDeclaration(
                request.databaseType(),
                request.customEngineBase());
    }

    /** Used by complement/sanitizer paths that only have databaseType + optional explicit base. */
    public static CustomEngineContext fromDeclaration(String databaseType, String customEngineBase) {
        String raw = databaseType != null ? databaseType.strip() : "custom";
        String lower = raw.toLowerCase(Locale.ROOT);
        String name = "custom";
        String base = customEngineBase != null && !customEngineBase.isBlank()
                ? customEngineBase.strip().toLowerCase(Locale.ROOT)
                : "mysql";

        if (lower.startsWith("custom::")) {
            String[] parts = raw.split("::", -1);
            if (parts.length >= 2 && !parts[1].isBlank()) {
                name = parts[1].trim();
            }
            if (parts.length > 2 && !parts[parts.length - 1].isBlank()) {
                base = parts[parts.length - 1].strip().toLowerCase(Locale.ROOT);
            }
        }
        return new CustomEngineContext(raw, name, base);
    }

    public QueryRequest asReferenceBaseRequest(QueryRequest original) {
        return new QueryRequest(
                original.query(),
                referenceBase,
                original.queryType(),
                original.dialect(),
                original.locale(),
                original.connection(),
                null);
    }
}
