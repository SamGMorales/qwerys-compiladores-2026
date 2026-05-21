package com.qwerys.qwerys_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplementAnalysisParserTest {

    private ComplementAnalysisParser parser;

    @BeforeEach
    void setUp() {
        parser = new ComplementAnalysisParser(new ObjectMapper());
    }

    @Test
    void parsesStructuredComplementJson() {
        String json = """
                {
                  "pedagogy": "Review window functions.",
                  "optimizationNotes": "",
                  "validityCorrection": { "apply": true, "correctedIsValid": true, "reason": "False syntax flag" },
                  "nativeReviews": [{ "referenceId": "OPT-003", "verdict": "PARTIAL", "comment": "Use FETCH FIRST on Oracle" }],
                  "additionalErrors": [],
                  "additionalWarnings": [{ "code": "AI-WARN-1", "severity": "WARNING" }],
                  "additionalOptimizations": [{
                    "ruleId": "AI-OPT-1",
                    "impact": "MEDIUM",
                    "description": "Add index hint",
                    "originalFragment": "SELECT * FROM t",
                    "optimizedFragment": "SELECT id FROM t"
                  }],
                  "syntaxCorrections": [{
                    "forErrorCode": "SYN-001-SQL",
                    "correctedQuery": "SELECT 1",
                    "explanation": "fixed"
                  }]
                }
                """;

        ComplementAnalysisResponse res = parser.parse(json, true, "groq:test", 10);
        assertTrue(res.success());
        assertEquals("Review window functions.", res.pedagogy());
        assertNotNull(res.validityCorrection());
        assertTrue(res.validityCorrection().apply());
        assertEquals(1, res.nativeReviews().size());
        assertEquals(1, res.additionalOptimizations().size());
        assertEquals("SELECT 1", res.syntaxCorrections().get(0).correctedQuery());
    }

    @Test
    void extractsAiSyntaxTreeIntoOverlay() {
        String json = """
                {
                  "pedagogy": "Ok",
                  "validityCorrection": { "apply": true, "correctedIsValid": true, "reason": "Valid CTE" },
                  "nativeReviews": [],
                  "additionalErrors": [],
                  "additionalWarnings": [],
                  "additionalOptimizations": [],
                  "syntaxCorrections": [],
                  "aiSyntaxTree": {
                    "type": "SELECT_STATEMENT",
                    "value": null,
                    "children": [
                      { "type": "COLUMN_LIST", "children": [
                          { "type": "COLUMN_REF", "value": "id", "children": [] }
                      ]},
                      { "type": "TABLE_REF", "value": "t", "children": [] }
                    ]
                  }
                }
                """;

        ComplementAnalysisResponse res = parser.parse(json, true, "groq:test", 7);
        assertTrue(res.success());
        assertNotNull(res.secondPassOverlay());
        assertNotNull(res.secondPassOverlay().astTree());
        assertEquals("SELECT_STATEMENT", res.secondPassOverlay().astTree().type());
        assertEquals(2, res.secondPassOverlay().astTree().children().size());
    }

    @Test
    void repairsJsonWhenModelCopiesStringConcatenationWithPlus() {
        // LLM often emits this invalid JSON when echoing SQL injection demo fragments:
        String broken = """
                {
                  "pedagogy": "Usa parametros, no concatenacion.",
                  "optimizationNotes": "",
                  "validityCorrection": { "apply": false, "correctedIsValid": false, "reason": "" },
                  "nativeReviews": [],
                  "additionalErrors": [],
                  "additionalWarnings": [{
                    "code": "AI-WARN-INJ",
                    "severity": "WARNING",
                    "message": "Riesgo de inyeccion por concatenacion."
                  }],
                  "additionalOptimizations": [{
                    "ruleId": "AI-OPT-SEC",
                    "impact": "HIGH",
                    "description": "Usar parametros",
                    "originalFragment": "WHERE Id = '" + userId + "'",
                    "optimizedFragment": "WHERE Id = @userId"
                  }],
                  "syntaxCorrections": []
                }
                """;

        ComplementAnalysisResponse res = parser.parse(broken, true, "openrouter:test", 12);
        assertTrue(res.success(), () -> "parse should succeed after JSON repair: " + res.error());
        assertEquals(1, res.additionalWarnings().size());
        assertEquals(1, res.additionalOptimizations().size());
        assertEquals("WHERE Id = '\" + userId + \"'", res.additionalOptimizations().get(0).originalFragment());
    }

    @Test
    void stripMarkdownFencesExtractsJsonObject() {
        String fenced = "```json\n{\"pedagogy\":\"ok\",\"nativeReviews\":[],\"additionalErrors\":[],"
                + "\"additionalWarnings\":[],\"additionalOptimizations\":[],\"syntaxCorrections\":[]}\n```";
        ComplementAnalysisResponse res = parser.parse(fenced, true, "groq:test", 1);
        assertTrue(res.success());
        assertEquals("ok", res.pedagogy());
    }
}
