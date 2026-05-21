package com.qwerys.qwerys_backend.model.ai;

import java.util.List;

public record AutocompleteRequest(
        String partialQuery,
        String databaseType,
        List<TableInfo> schema,
        String locale
) {
}
