package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchAnalyzerCrossScriptTest {

    private final ElasticsearchAnalyzer analyzer = new ElasticsearchAnalyzer();

    @Test
    void crossScript_twoQueriesWithoutSize_emitsCross001() {
        String raw = "{\"query\":{\"match_all\":{}}}; {\"query\":{\"match_all\":{}}}";
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "ES-CROSS-001".equals(e.code())));
    }

    @Test
    void crossScript_mixAggAndSearch_emitsCross002() {
        String raw = "{\"aggs\":{\"x\":{\"terms\":{\"field\":\"f\"}}}}; {\"query\":{\"match_all\":{}}}";
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "ES-CROSS-002".equals(e.code())));
    }

    @Test
    void crossScript_singleJsonObject_noCrossRules() {
        String raw = "{\"query\":{\"match_all\":{}}}";
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH);
        assertFalse(cross.stream().anyMatch(e -> e.code().startsWith("ES-CROSS-")));
    }

    @Test
    void crossScript_restPrefixedPipeline_then_search_emitsCross003() {
        String pipe = """
                PUT _ingest/pipeline/p1
                {"processors":[{"set":{"field":"x","value":1}}],"on_failure":[{"fail":{"message":"e"}}]}""";
        String raw = pipe + "; {\"query\":{\"match_all\":{}},\"size\":10}";
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "ES-CROSS-003".equals(e.code())));
        assertFalse(cross.stream().anyMatch(e -> "ES-CROSS-001".equals(e.code())));
    }

    @Test
    void crossScript_twoIngestBodies_emitsCross004() {
        String p1 = "{\"processors\":[{\"set\":{\"field\":\"a\",\"value\":1}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        String p2 = "{\"processors\":[{\"set\":{\"field\":\"b\",\"value\":2}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(p1 + "; " + p2, Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "ES-CROSS-004".equals(e.code())));
        assertFalse(cross.stream().anyMatch(e -> "ES-CROSS-003".equals(e.code())));
    }

    @Test
    void crossScript_ingestReindexAndSearch_emitsCross003() {
        String ingest = "{\"processors\":[{\"fail\":{\"message\":\"x\"}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        String reindex = "{\"source\":{\"index\":\"a\"},\"dest\":{\"index\":\"b\"}}";
        String search = "{\"query\":{\"match_all\":{}},\"size\":1}";
        String raw = ingest + "; " + reindex + "; " + search;
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "ES-CROSS-003".equals(e.code())));
    }

    @Test
    void crossScript_postSearchPrefixed_twoQueriesWithoutSize_emitsCross001() {
        String raw = """
                POST my-index/_search
                {"query":{"match_all":{}}}
                ; POST my-index/_search
                {"query":{"match_all":{}}}""";
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "ES-CROSS-001".equals(e.code())));
    }

    @Test
    void crossScript_searchTemplateAndSearch_emitsCross003() {
        String tpl = """
                POST my-index/_search/template
                {"id":"t1","params":{}}""";
        String search = "{\"query\":{\"match_all\":{}},\"size\":10}";
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(tpl + "; " + search, Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "ES-CROSS-003".equals(e.code())));
    }
}
