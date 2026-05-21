package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisRequest;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisResponse;
import com.qwerys.qwerys_backend.model.ai.NativeFindingReviewDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomEngineAnalysisSupportTest {

    private static final String N1QL = """
            SELECT name, age
            FROM `app`.`users`.`profiles`
            WHERE country = "GT";
            """;

    @Test
    void detectsSqlLikeN1qlQuery() {
        assertTrue(CustomEngineAnalysisSupport.isSqlLikeDocumentQuery(N1QL));
    }

    @Test
    void doesNotFlagMongoShellAsSqlLike() {
        assertTrue(!CustomEngineAnalysisSupport.isSqlLikeDocumentQuery("db.users.find({ country: 'GT' })"));
    }

    @Test
    void sanitizerFlipsAgreeWrongEngineToDisagreeForCustomSqlLike() {
        ComplementAnalysisRequest req = new ComplementAnalysisRequest(
                N1QL,
                "custom::Couchbase::mongodb",
                "es",
                true,
                false,
                null,
                null,
                "mongodb",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null);

        List<NativeFindingReviewDto> reviews = List.of(
                new NativeFindingReviewDto(
                        "CB-WRONG-ENGINE",
                        "AGREE",
                        "No es N1QL"));

        List<NativeFindingReviewDto> adjusted =
                CustomEngineAnalysisSupport.adjustWrongEngineReviewsForCustomSqlLike(
                        reviews, req, Locale.forLanguageTag("es"));

        assertEquals(1, adjusted.size());
        assertEquals("DISAGREE", adjusted.get(0).verdict());
        assertTrue(adjusted.get(0).comment().contains("N1QL"));
    }

    @Test
    void sanitizerKeepsAgreeWrongEngineForMongoShellOnCustomMongo() {
        ComplementAnalysisRequest req = new ComplementAnalysisRequest(
                "db.users.find({})",
                "custom::MyDocStore::mongodb",
                "en",
                false,
                false,
                null,
                null,
                "mongodb",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null);

        List<NativeFindingReviewDto> reviews = List.of(
                new NativeFindingReviewDto("MGO-WRONG-ENGINE", "AGREE", "Looks like SQL"));

        List<NativeFindingReviewDto> adjusted =
                CustomEngineAnalysisSupport.adjustWrongEngineReviewsForCustomSqlLike(
                        reviews, req, Locale.ENGLISH);

        assertEquals("AGREE", adjusted.get(0).verdict());
    }

    @Test
    void complementSanitizerAppliesCustomWrongEngineGuard() {
        ComplementAnalysisRequest req = new ComplementAnalysisRequest(
                N1QL,
                "custom::Couchbase::mongodb",
                "es",
                true,
                false,
                null,
                null,
                "mongodb",
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null);

        ComplementAnalysisResponse raw = ComplementAnalysisResponse.ok(
                "Pedagogia",
                "",
                null,
                List.of(new NativeFindingReviewDto("CB-WRONG-ENGINE", "AGREE", "x")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                true,
                "groq:test",
                1L);

        ComplementAnalysisResponse sanitized = ComplementAnalysisSanitizer.sanitize(raw, req);
        assertEquals("DISAGREE", sanitized.nativeReviews().get(0).verdict());
    }
}
