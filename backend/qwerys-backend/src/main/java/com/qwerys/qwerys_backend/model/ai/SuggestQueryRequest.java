package com.qwerys.qwerys_backend.model.ai;

import java.util.List;

public record SuggestQueryRequest(
        String description,
        String databaseType,
        List<TableInfo> schema,
        String locale
) {
}
