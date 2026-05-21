package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.ElasticsearchAnalyzer;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elasticsearch search perf, aggregations, ingest/ILM/snapshot edges, and cross-script analysis.
 */
class ElasticsearchAdvancedTest {

    private final ElasticsearchAnalyzer analyzer = new ElasticsearchAnalyzer();

    private static Set<String> codes(List<SemanticError> f) {
        return f.stream().map(SemanticError::code).collect(Collectors.toSet());
    }

    private static String deepNestedQuery(int wrappers) {
        String inner = "{\"match_all\":{}}";
        for (int i = 0; i < wrappers; i++) {
            inner = "{\"bool\":{\"must\":[" + inner + "]}}";
        }
        return "{\"query\":" + inner + "}";
    }

    private static String deepAggs(int levels) {
        String leaf = "\"leaf\":{\"terms\":{\"field\":\"f\",\"size\":10}}";
        for (int i = levels - 1; i >= 1; i--) {
            leaf = "\"a" + i + "\":{\"terms\":{\"field\":\"f" + i + "\"},\"aggs\":{" + leaf + "}}";
        }
        return "{\"size\":0,\"aggs\":{" + leaf + "}}";
    }

    @Test
    void basicTermQuery_noMatchAll() {
        String json = "{\"query\":{\"term\":{\"status\":{\"value\":\"open\"}}}}";
        assertFalse(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-MATCH-ALL"));
    }

    @Test
    void matchAll_emitsMatchAll() {
        String json = "{\"query\":{\"match_all\":{}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-MATCH-ALL"));
    }

    @Test
    void sizeOverLimit_emitsSizeLimit() {
        String json = "{\"query\":{\"match_all\":{}},\"size\":10001}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-SIZE-LIMIT"));
    }

    @Test
    void deepPagination_emitsDeepPage() {
        String json = "{\"query\":{\"match_all\":{}},\"from\":9999,\"size\":5}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-DEEP-PAGE"));
    }

    @Test
    void nestedQueryDepth_emitsNestedDepth() {
        assertTrue(codes(analyzer.analyze(deepNestedQuery(8), Locale.ENGLISH)).contains("ES-NESTED-DEPTH"));
    }

    @Test
    void scriptQuery_emitsScriptWarning() {
        String json = "{\"query\":{\"script\":{\"script\":{\"source\":\"doc['x'].value > 0\"}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-SCRIPT"));
    }

    @Test
    void avgBucketAsQueryRoot_emitsStruct001() {
        String json = "{\"query\":{\"avg_bucket\":{\"buckets_path\":\"x\"}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-STRUCT-001"));
    }

    @Test
    void trackTotalHitsFalse_emitsTrackTotal() {
        String json = "{\"query\":{\"match_all\":{}},\"track_total_hits\":false}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-TRACK-TOTAL"));
    }

    @Test
    void sourceTrue_emitsSourceFetch() {
        String json = "{\"query\":{\"match_all\":{}},\"_source\":true}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-SOURCE-FETCH"));
    }

    @Test
    void explainTrue_emitsExplain() {
        String json = "{\"query\":{\"match_all\":{}},\"explain\":true}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-EXPLAIN"));
    }

    @Test
    void profileTrue_emitsProfile() {
        String json = "{\"query\":{\"match_all\":{}},\"profile\":true}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-PROFILE"));
    }

    @Test
    void deepAggs_emitsAggDepth() {
        assertTrue(codes(analyzer.analyze(deepAggs(6), Locale.ENGLISH)).contains("ES-AGG-DEPTH"));
    }

    @Test
    void termsAggNoSize_emitsTermsNoSize() {
        String json = "{\"size\":0,\"aggs\":{\"t\":{\"terms\":{\"field\":\"user\"}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-TERMS-NO-SIZE"));
    }

    @Test
    void dateHistogramLegacyInterval_emitsDeprecated() {
        String json = "{\"size\":0,\"aggs\":{\"h\":{\"date_histogram\":{\"field\":\"@timestamp\",\"interval\":\"1h\"}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-DATE-HIST-DEPRECATED"));
    }

    @Test
    void cardinalityHighPrecision_emitsCardinalityThreshold() {
        String json = "{\"size\":0,\"aggs\":{\"c\":{\"cardinality\":{\"field\":\"uid\",\"precision_threshold\":50000}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-CARDINALITY-THRESHOLD"));
    }

    @Test
    void bucketScriptMissingPath_emitsBucketScriptPath() {
        String json = "{\"size\":0,\"aggs\":{\"x\":{\"terms\":{\"field\":\"f\",\"size\":5},\"aggs\":{\"bs\":{\"bucket_script\":{\"script\":\"1\"}}}}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-BUCKET-SCRIPT-PATH"));
    }

    @Test
    void bucketSelectorIncomplete_emitsBucketSelFields() {
        String json = "{\"size\":0,\"aggs\":{\"x\":{\"terms\":{\"field\":\"f\",\"size\":5},\"aggs\":{\"sel\":{\"bucket_selector\":{\"buckets_path\":{\"a\":\"x\"}}}}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-BUCKET-SEL-FIELDS"));
    }

    @Test
    void movingAvg_emitsDeprecated() {
        String json = "{\"size\":0,\"aggs\":{\"t\":{\"date_histogram\":{\"field\":\"@timestamp\",\"calendar_interval\":\"1d\"},\"aggs\":{\"m\":{\"moving_avg\":{\"buckets_path\":\"t\"}}}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-MOVING-AVG-DEPRECATED"));
    }

    @Test
    void nestedAggMissingPath_emitsNestedNoPath() {
        String json = "{\"size\":0,\"aggs\":{\"n\":{\"nested\":{},\"aggs\":{\"x\":{\"terms\":{\"field\":\"k\",\"size\":3}}}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-NESTED-NO-PATH"));
    }

    @Test
    void reverseNestedOutsideNested_emitsRevNested() {
        String json = "{\"size\":0,\"aggs\":{\"x\":{\"terms\":{\"field\":\"f\",\"size\":2},\"aggs\":{\"rn\":{\"reverse_nested\":{}}}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-REV-NESTED-CONTEXT"));
    }

    @Test
    void crossScript_twoSearches_emitsCross001() {
        String raw = "{\"query\":{\"match_all\":{}}}; {\"query\":{\"match_all\":{}}}";
        assertTrue(codes(analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH)).contains("ES-CROSS-001"));
    }

    @Test
    void crossScript_aggThenSearch_emitsCross002() {
        String raw = "{\"aggs\":{\"x\":{\"terms\":{\"field\":\"f\"}}}}; {\"query\":{\"match_all\":{}}}";
        assertTrue(codes(analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH)).contains("ES-CROSS-002"));
    }

    @Test
    void crossScript_pipelineThenSearch_emitsCross003() {
        String ingest = "{\"processors\":[{\"set\":{\"field\":\"a\",\"value\":1}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        String search = "{\"query\":{\"match_all\":{}},\"size\":10}";
        assertTrue(codes(analyzer.analyzeCrossScriptOnly(ingest + "; " + search, Locale.ENGLISH)).contains("ES-CROSS-003"));
    }

    @Test
    void ingestMissingOnFailure_emitsIp001() {
        String json = "{\"processors\":[{\"set\":{\"field\":\"x\",\"value\":1}}]}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-IP-001"));
    }

    @Test
    void ilmHotWithoutRollover_emitsIlm001() {
        String json = "{\"policy\":{\"phases\":{\"hot\":{\"actions\":{\"set_priority\":{\"priority\":100}}}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-ILM-001"));
    }

    @Test
    void ilmNoDelete_emitsIlm005() {
        String json = "{\"policy\":{\"phases\":{\"hot\":{\"actions\":{\"rollover\":{\"max_age\":\"1d\"}}}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-ILM-005"));
    }

    @Test
    void snapshotRepoNoCompress_emitsSnp001() {
        String json = "{\"type\":\"fs\",\"settings\":{\"location\":\"/backup\"}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-SNP-001"));
    }

    @Test
    void wildcardLeading_emitsWildcardLead() {
        String json = "{\"query\":{\"wildcard\":{\"user\":{\"value\":\"*admin\"}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-WILDCARD-LEAD"));
    }

    @Test
    void regexpQuery_emitsRegex() {
        String json = "{\"query\":{\"regexp\":{\"user\":{\"value\":\".*@host\"}}}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-REGEX"));
    }

    @Test
    void knnMissingK_emitsKnnK() {
        String json = "{\"knn\":{\"field\":\"v\",\"query_vector\":[0.1,0.2],\"num_candidates\":100}}";
        assertTrue(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-KNN-K"));
    }

    @Test
    void multiStageIngestProcessors_chain() {
        String json = "{\"processors\":["
                + "{\"set\":{\"field\":\"a\",\"value\":1}},"
                + "{\"set\":{\"field\":\"b\",\"value\":2}},"
                + "{\"set\":{\"field\":\"c\",\"value\":3}}],"
                + "\"on_failure\":[{\"fail\":{\"message\":\"e\"}}]}";
        assertFalse(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-IP-001"));
    }

    @Test
    void largeBoolQuery_stillParses() {
        String json = deepNestedQuery(4);
        assertFalse(codes(analyzer.analyze(json, Locale.ENGLISH)).contains("ES-SYNTAX-001"));
    }
}
