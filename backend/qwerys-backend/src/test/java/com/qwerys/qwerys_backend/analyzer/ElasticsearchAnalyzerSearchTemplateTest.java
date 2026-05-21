package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElasticsearchAnalyzerSearchTemplateTest {

    private final ElasticsearchAnalyzer analyzer = new ElasticsearchAnalyzer();

    private static boolean hasCode(List<SemanticError> findings, String code) {
        return findings.stream().anyMatch(e -> code.equals(e.code()));
    }

    @Test
    void putStoredMustache_withInterpolation_emitsTpl004() {
        String raw = """
                PUT _scripts/my_tpl
                {"script":{"lang":"mustache","source":"{\\"query\\":{\\"match\\":{\\"m\\":\\"{{q}}\\"}}}"}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-TPL-004"));
    }

    @Test
    void putStoredMustache_sqlInjectionLike_emitsTpl003() {
        String raw = """
                PUT _scripts/bad_tpl
                {"script":{"lang":"mustache","source":"select * from t where 1=1 and {{x}}"}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-TPL-003"));
    }

    @Test
    void putStoredMustache_sectionWithoutInverted_emitsTpl002() {
        String raw = """
                PUT _scripts/opt_tpl
                {"script":{"lang":"mustache","source":"{{#show}}visible{{/show}}"}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-TPL-002"));
    }

    @Test
    void postSearchTemplate_inline_missingParam_emitsTpl001() {
        String raw = """
                POST my-index/_search/template
                {"source":"{\\"query\\":{\\"match\\":{\\"message\\":\\"{{msg}}\\"}}}","params":{"other":1}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-TPL-001"));
    }

    @Test
    void postSearchTemplate_byIdOnly_emitsTpl004() {
        String raw = """
                POST my-index/_search/template
                {"id":"my_tpl","params":{"q":"x"}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-TPL-004"));
    }

    @Test
    void postRenderTemplate_allParamsUsed_emitsRnd001() {
        String raw = """
                POST _render/template
                {"source":"{\\"query\\":{\\"term\\":{\\"k\\":\\"{{p}}\\"}}}","params":{"p":"v"}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-RND-001"));
        assertFalse(hasCode(f, "ES-TPL-001"));
        assertFalse(hasCode(f, "ES-RND-002"));
    }

    @Test
    void postRenderTemplate_staticSourceNoMustache_emptyParams_emitsRnd001() {
        String raw = """
                POST _render/template
                {"source":"{\\"query\\":{\\"match_all\\":{}}}"}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-RND-001"));
    }

    @Test
    void postRenderTemplate_extraParams_emitsRnd002_notRnd001() {
        String raw = """
                POST _render/template/tname
                {"source":"{\\"query\\":{\\"match_all\\":{}}}","params":{"unused":1,"extra":2}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-RND-002"));
        assertFalse(hasCode(f, "ES-RND-001"));
    }

    @Test
    void postRenderTemplate_namedPath_extraParams_emitsRnd002_notRnd001() {
        String raw = """
                POST _render/template/my_template
                {"source":"Hello {{name}}","params":{"name":"a","orphan":true}}""";
        List<SemanticError> f = analyzer.analyze(raw, Locale.ENGLISH);
        assertTrue(hasCode(f, "ES-RND-002"));
        assertFalse(hasCode(f, "ES-RND-001"));
    }
}
