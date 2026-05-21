package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.history.QueryHistoryService;
import com.qwerys.qwerys_backend.model.QueryRequest;
import com.qwerys.qwerys_backend.optimization.OptimizationEngine;
import org.mockito.Mockito;

import java.util.Locale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Builds {@link QueryAnalysisService} for unit tests with custom-engine AI disabled by default.
 */
public final class QueryAnalysisServiceTestFixtures {

    private QueryAnalysisServiceTestFixtures() {}

    public static QueryAnalysisService create() {
        OptimizationEngine optimizationEngine = new OptimizationEngine();
        QueryHistoryService historyService = Mockito.mock(QueryHistoryService.class);
        CustomEngineAiAnalyzer customEngineAiAnalyzer = Mockito.mock(CustomEngineAiAnalyzer.class);
        QueryAnalysisService service =
                new QueryAnalysisService(optimizationEngine, historyService, customEngineAiAnalyzer);
        when(customEngineAiAnalyzer.isAvailable()).thenReturn(false);
        when(customEngineAiAnalyzer.approximateFallback(any(), any(), any(), anyLong()))
                .thenAnswer(invocation -> {
                    QueryRequest request = invocation.getArgument(0);
                    CustomEngineContext ctx = invocation.getArgument(1);
                    Locale ui = invocation.getArgument(2);
                    long startNano = invocation.getArgument(3);
                    return service.analyzeNativeOnly(
                            ctx.asReferenceBaseRequest(request),
                            false,
                            ui,
                            startNano);
                });
        return service;
    }
}
