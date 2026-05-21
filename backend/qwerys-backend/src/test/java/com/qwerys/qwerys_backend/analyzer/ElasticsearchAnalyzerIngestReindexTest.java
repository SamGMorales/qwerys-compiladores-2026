package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchAnalyzerIngestReindexTest {

    private final ElasticsearchAnalyzer analyzer = new ElasticsearchAnalyzer();

    private static boolean hasCode(List<SemanticError> findings, String code) {
        return findings.stream().anyMatch(e -> code.equals(e.code()));
    }

    @Test
    void ingest_restPrefix_putIngestPipeline_stillParses() {
        String raw = """
                PUT _ingest/pipeline/my-pipeline
                {"processors":[{"set":{"field":"x","value":1}}]}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-IP-001"));
    }

    @Test
    void ingest_ip001_noOnFailure() {
        String json = "{\"processors\":[{\"set\":{\"field\":\"a\",\"value\":1}}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-IP-001"));
    }

    @Test
    void ingest_ip001_withOnFailure_noWarning() {
        String json = "{\"processors\":[{\"set\":{\"field\":\"a\",\"value\":1}}],"
                + "\"on_failure\":[{\"set\":{\"field\":\"error\",\"value\":true}}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(f.stream().noneMatch(e -> "ES-IP-001".equals(e.code())));
    }

    @Test
    void ingest_ip001_suppressedByProcessorLevelOnFailure() {
        String json = "{\"processors\":[{\"set\":{\"field\":\"a\",\"value\":1},"
                + "\"on_failure\":[{\"fail\":{\"message\":\"x\"}}]}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(f.stream().noneMatch(e -> "ES-IP-001".equals(e.code())));
    }

    @Test
    void ingest_ip002_moreThan20Processors() {
        StringBuilder sb = new StringBuilder("{\"processors\":[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"set\":{\"field\":\"f").append(i).append("\",\"value\":1}}");
        }
        sb.append("],\"on_failure\":[{\"fail\":{\"message\":\"x\"}}]}");
        List<SemanticError> f = analyzer.analyze(sb.toString(), Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-IP-002"));
    }

    @Test
    void ingest_ip003_nestedPipelineProcessor() {
        String json = "{\"processors\":[{\"pipeline\":{\"name\":\"child\"}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-IP-003"));
    }

    @Test
    void ingest_ip004_foreachMissingProcessor() {
        String json = "{\"processors\":[{\"foreach\":{\"field\":\"tags\"}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-IP-004"));
    }

    @Test
    void ingest_ip005_scriptProcessorNoLang() {
        String json = "{\"processors\":[{\"script\":{\"source\":\"ctx.foo = 1\"}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-IP-005"));
    }

    @Test
    void ingest_ip006_grokManyCaptures() {
        String pattern = "%{WORD:a1}%{WORD:a2}%{WORD:a3}%{WORD:a4}%{WORD:a5}%{WORD:a6}";
        String json = "{\"processors\":[{\"grok\":{\"field\":\"message\",\"patterns\":[\"" + pattern + "\"]}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-IP-006"));
    }

    @Test
    void ingest_ip007_removeBeforeRenameSameField() {
        String json = "{\"processors\":["
                + "{\"remove\":{\"field\":\"login\"}},"
                + "{\"rename\":{\"field\":\"login\",\"target_field\":\"username\"}}"
                + "],\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-IP-007"));
    }

    @Test
    void reindex_rx001_noSlices() {
        String json = "{\"source\":{\"index\":\"a\"},\"dest\":{\"index\":\"b\"}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-RX-001"));
        assertTrue(hasCode(f, "ES-RX-003"));
    }

    @Test
    void reindex_rx002_scriptUsesSourceWithoutOpGuard() {
        String json = "{\"source\":{\"index\":\"a\"},\"dest\":{\"index\":\"b\"},"
                + "\"script\":{\"source\":\"ctx._source.foo = 'bar'\"}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-RX-002"));
    }

    @Test
    void reindex_rx002_scriptWithCtxOp_noRx002() {
        String json = "{\"source\":{\"index\":\"a\"},\"dest\":{\"index\":\"b\"},"
                + "\"script\":{\"source\":\"if (ctx.op == 'index') { ctx._source.x = 1 }\"}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(f.stream().noneMatch(e -> "ES-RX-002".equals(e.code())));
    }

    @Test
    void reindex_remote_noRx003() {
        String json = "{\"source\":{\"remote\":{\"host\":\"http://other:9200\"},\"index\":\"a\"},"
                + "\"dest\":{\"index\":\"b\"},\"slices\":4}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(f.stream().noneMatch(e -> "ES-RX-003".equals(e.code())));
        assertTrue(f.stream().noneMatch(e -> "ES-RX-001".equals(e.code())));
    }

    @Test
    void searchBody_notTreatedAsIngest() {
        String json = "{\"query\":{\"match_all\":{}},\"size\":10}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(f.stream().noneMatch(e -> e.code().startsWith("ES-IP-")));
        assertTrue(f.stream().noneMatch(e -> e.code().startsWith("ES-RX-")));
        assertTrue(hasCode(f, "ES-MATCH-ALL"));
    }

    @Test
    void ingest_painlessStillAnalyzedInsideScriptProcessor() {
        String json = "{\"processors\":[{\"script\":{\"lang\":\"painless\",\"source\":\"while (true) { }\"}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        List<String> codes = f.stream().map(SemanticError::code).collect(Collectors.toList());
        assertTrue(codes.stream().anyMatch(c -> c.startsWith("PNL-")));
    }
}
