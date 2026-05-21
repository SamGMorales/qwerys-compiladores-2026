package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchAnalyzerIlmSnapshotTest {

    private final ElasticsearchAnalyzer analyzer = new ElasticsearchAnalyzer();

    private static boolean hasCode(List<SemanticError> findings, String code) {
        return findings.stream().anyMatch(e -> code.equals(e.code()));
    }

    @Test
    void ilm_prefix_putPolicy_triggersIlm001WithoutRollover() {
        String raw = """
                PUT _ilm/policy/logs
                {"policy":{"phases":{"hot":{"actions":{"set_priority":{"priority":100}}}}}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-ILM-001"));
    }

    @Test
    void ilm_hotWithRollover_noIlm001() {
        String json = "{\"policy\":{\"phases\":{\"hot\":{\"actions\":{\"rollover\":{\"max_age\":\"1d\"}}}}}}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertFalse(hasCode(f, "ES-ILM-001"));
    }

    @Test
    void ilm_noDeletePhase_emitsIlm005() {
        String json = "{\"policy\":{\"phases\":{\"hot\":{\"actions\":{\"rollover\":{\"max_size\":\"50gb\"}}}}}}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-ILM-005"));
    }

    @Test
    void ilm_deletePhaseWithoutMinAge_emitsIlm002() {
        String json = "{\"policy\":{\"phases\":{\"delete\":{\"actions\":{\"delete\":{}}}}}}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-ILM-002"));
    }

    @Test
    void ilm_warmShrinkBeforeForcemerge_emitsIlm003() {
        String json = "{\"policy\":{\"phases\":{\"warm\":{\"actions\":{"
                + "\"shrink\":{\"number_of_shards\":1},\"forcemerge\":{\"max_num_segments\":1}"
                + "}}}}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-ILM-003"));
    }

    @Test
    void ilm_coldWithoutFreeze_emitsIlm004() {
        String json = "{\"policy\":{\"phases\":{\"cold\":{\"actions\":{\"allocate\":{\"number_of_replicas\":0}}}}}}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-ILM-004"));
    }

    @Test
    void snapshot_repoWithoutCompress_emitsSnp001() {
        String json = "{\"type\":\"fs\",\"settings\":{\"location\":\"/backup\"}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-SNP-001"));
    }

    @Test
    void snapshot_repoWithCompress_noSnp001() {
        String json = "{\"type\":\"fs\",\"settings\":{\"location\":\"/backup\",\"compress\":true}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertFalse(hasCode(f, "ES-SNP-001"));
    }

    @Test
    void snapshot_repo_emitsSnp003WhenNoScheduleHint() {
        String json = "{\"type\":\"fs\",\"settings\":{\"location\":\"/backup\",\"compress\":true}}";
        List<SemanticError> f = analyzer.analyze(json, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-SNP-003"));
    }

    @Test
    void snapshot_restoreWithoutRename_emitsSnp002() {
        String raw = """
                POST _snapshot/my_repo/snap1/_restore
                {"indices":"*"}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-SNP-002"));
    }

    @Test
    void snapshot_restoreWithRenamePattern_noSnp002() {
        String raw = """
                POST _snapshot/my_repo/snap1/_restore
                {"indices":"*","rename_pattern":"(.+)","rename_replacement":"restored-$1"}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertFalse(hasCode(f, "ES-SNP-002"));
    }

    @Test
    void snapshot_createIncludeGlobalState_emitsSnp004() {
        String raw = """
                PUT _snapshot/repo/snap1
                {"indices":"*","include_global_state":true}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-SNP-004"));
    }

    @Test
    void crossScript_ilmAndSearch_emitsCross003() {
        String ilm = "{\"policy\":{\"phases\":{\"hot\":{\"actions\":{\"rollover\":{\"max_age\":\"1d\"}}}}}}}";
        String search = "{\"query\":{\"match_all\":{}},\"size\":10}";
        String raw = "PUT _ilm/policy/p1\n" + ilm + "; " + search;
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH);
        assertTrue(cross.stream().anyMatch(e -> "ES-CROSS-003".equals(e.code())));
    }
}
