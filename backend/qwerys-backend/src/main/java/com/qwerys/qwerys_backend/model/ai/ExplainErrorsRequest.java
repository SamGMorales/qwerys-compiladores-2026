package com.qwerys.qwerys_backend.model.ai;

import java.util.List;

public record ExplainErrorsRequest(
        String query,
        String databaseType,
        List<AnalysisErrorDto> errors,
        String locale
) {
}
