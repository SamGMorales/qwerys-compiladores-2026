package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.ai.AiFailureReason;
import com.qwerys.qwerys_backend.model.ai.AutocompleteRequest;
import com.qwerys.qwerys_backend.model.ai.GenerateQueryRequest;
import com.qwerys.qwerys_backend.model.ai.SuggestQueryRequest;
import com.qwerys.qwerys_backend.model.ai.TableInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;


class RuleBasedAiFallbackTest {

    private final RuleBasedAiFallback fallback = new RuleBasedAiFallback();

    @Test
    void suggestQuery_usesSchemaTableWhenPresent() {
        var req = new SuggestQueryRequest(
                "listar usuarios",
                "mysql",
                List.of(new TableInfo("users", List.of("id", "name"))),
                "es");
        String sql = fallback.suggestQuery(req);
        assertTrue(sql.contains("users"));
        assertTrue(sql.contains("id") || sql.contains("*"));
    }

    @Test
    void autocomplete_appendsTableAfterFrom() {
        var req = new AutocompleteRequest(
                "SELECT * FROM ",
                "mysql",
                List.of(new TableInfo("orders", List.of("id"))),
                "es");
        String sql = fallback.autocomplete(req);
        assertTrue(sql.contains("orders"));
    }

    @Test
    void generateQuery_buildsSelectWithColumns() {
        var req = new GenerateQueryRequest(
                "SELECT",
                "products",
                List.of("id", "name"),
                "active = 1",
                "mysql",
                "es");
        String sql = fallback.generateQuery(req);
        assertTrue(sql.startsWith("SELECT"));
        assertTrue(sql.contains("products"));
        assertTrue(sql.contains("id"));
        assertFalse(sql.contains("SELECT *"));
    }

    // ─── Honest failure message tests ──────────────────────────────────────

    @Test
    void aiUnavailableMessage_rateLimitSpanish_mentionsLimiteAndGroq() {
        String msg = RuleBasedAiFallback.aiUnavailableMessage(Locale.forLanguageTag("es"), AiFailureReason.RATE_LIMIT);
        assertTrue(msg.toLowerCase(Locale.ROOT).contains("límite") || msg.toLowerCase(Locale.ROOT).contains("limite"),
                "Spanish rate-limit message should mention 'límite': " + msg);
        assertTrue(msg.contains("Groq"), "Should mention Groq: " + msg);
    }

    @Test
    void aiUnavailableMessage_rateLimitEnglish_mentionsDailyLimit() {
        String msg = RuleBasedAiFallback.aiUnavailableMessage(Locale.ENGLISH, AiFailureReason.RATE_LIMIT);
        assertTrue(msg.contains("Daily") || msg.contains("daily") || msg.contains("limit"),
                "English rate-limit message should mention limit: " + msg);
    }

    @Test
    void aiUnavailableMessage_invalidKey_mentionsKey() {
        String enMsg = RuleBasedAiFallback.aiUnavailableMessage(Locale.ENGLISH, AiFailureReason.INVALID_KEY);
        assertTrue(enMsg.toLowerCase(Locale.ROOT).contains("key"), "Should mention 'key': " + enMsg);

        String esMsg = RuleBasedAiFallback.aiUnavailableMessage(Locale.forLanguageTag("es"), AiFailureReason.INVALID_KEY);
        assertTrue(esMsg.toLowerCase(Locale.ROOT).contains("clave"), "Spanish should mention 'clave': " + esMsg);
    }

    @Test
    void aiUnavailableMessage_notConfigured_mentionsConfiguration() {
        String msg = RuleBasedAiFallback.aiUnavailableMessage(Locale.ENGLISH, AiFailureReason.NOT_CONFIGURED);
        assertTrue(msg.toLowerCase(Locale.ROOT).contains("configur"),
                "Should mention configuration: " + msg);
    }

    @Test
    void aiUnavailableMessage_timeout_mentionsTimeout() {
        String esMsg = RuleBasedAiFallback.aiUnavailableMessage(Locale.forLanguageTag("es"), AiFailureReason.TIMEOUT);
        assertTrue(esMsg.toLowerCase(Locale.ROOT).contains("timeout") || esMsg.toLowerCase(Locale.ROOT).contains("tiempo"),
                "Spanish timeout message should mention timeout: " + esMsg);
    }

    @Test
    void aiUnavailableMessage_parseError_mentionsParsed() {
        String msg = RuleBasedAiFallback.aiUnavailableMessage(Locale.ENGLISH, AiFailureReason.PARSE_ERROR);
        assertTrue(msg.toLowerCase(Locale.ROOT).contains("parsed") || msg.toLowerCase(Locale.ROOT).contains("parse"),
                "Should mention parse: " + msg);
    }

    @Test
    void aiUnavailableMessage_nullReason_doesNotThrow() {
        String msg = assertDoesNotThrow(
                () -> RuleBasedAiFallback.aiUnavailableMessage(Locale.ENGLISH, null));
        assertNotNull(msg);
        assertFalse(msg.isBlank());
    }

    @Test
    void aiFailureReason_detects429FromMessage() {
        var ex = new IllegalStateException("groq API HTTP 429: rate_limit_exceeded tokens per day");
        assertEquals(AiFailureReason.RATE_LIMIT, AiFailureReason.from(ex));
    }

    @Test
    void aiFailureReason_detectsTimeoutFromMessage() {
        var ex = new IllegalStateException("groq network error: Read timed out");
        assertEquals(AiFailureReason.TIMEOUT, AiFailureReason.from(ex));
    }

    @Test
    void aiFailureReason_detectsNotConfiguredFromMessage() {
        var ex = new IllegalStateException("groq API key is not configured");
        assertEquals(AiFailureReason.NOT_CONFIGURED, AiFailureReason.from(ex));
    }

    @Test
    void aiFailureReason_returnsUnknownForNull() {
        assertEquals(AiFailureReason.UNKNOWN, AiFailureReason.from(null));
    }
}
