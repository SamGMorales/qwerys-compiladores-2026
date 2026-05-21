package com.qwerys.qwerys_backend.service;

import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisRequest;
import com.qwerys.qwerys_backend.model.ai.ComplementAnalysisResponse;
import com.qwerys.qwerys_backend.model.ai.OptimizationDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the dialect-mismatch filter, diagnostic-only matcher and the
 * folklore-fragments list now also cover the supported NoSQL engines
 * (MongoDB, Cassandra, Redis, Elasticsearch, DynamoDB). Before this change the
 * filter fell through to {@code default -> false} for all NoSQL engines except
 * DynamoDB, which let the LLM emit SQL DML/DDL leakage, .explain() diagnostics
 * and folklore advice (ALLOW FILTERING, KEYS *, $where, etc.) unsanitized.
 */
class ComplementAnalysisSanitizerNoSqlTest {

    private static ComplementAnalysisRequest requestFor(String engine, String query) {
        return new ComplementAnalysisRequest(
                query,
                engine,
                "en",
                true,
                false,
                engine,
                engine,
                null,
                null,
                List.of(),
                List.of(),
                List.of(), null, null, null, null, null);
    }

    private static ComplementAnalysisResponse responseWith(OptimizationDto... opts) {
        return ComplementAnalysisResponse.ok(
                "pedagogy",
                "",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(opts),
                List.of(),
                null,
                true,
                "test",
                0L);
    }

    private static OptimizationDto opt(String id, String description, String original, String optimized) {
        return new OptimizationDto(id, "MEDIUM", description, original, optimized);
    }

    // ----- Cross-dialect leakage (isInvalidDialectOptimization) -----

    @Test
    void sqlSelectIsFilteredWhenEngineIsMongo() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-1", "Use a SELECT statement", "{}",
                        "SELECT name FROM users WHERE active = true")),
                requestFor("mongodb", "db.users.find({active: true})"));
        assertEquals(0, out.additionalOptimizations().size(),
                "Plain SQL SELECT must not be emitted for a MongoDB query");
    }

    @Test
    void joinIsFilteredForCassandra() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-2", "Use a JOIN", "SELECT * FROM users",
                        "SELECT u.name, o.id FROM users u JOIN orders o ON o.uid = u.id")),
                requestFor("cassandra", "SELECT * FROM users"));
        assertEquals(0, out.additionalOptimizations().size(),
                "Cassandra (CQL) does not support JOIN");
    }

    @Test
    void groupByIsFilteredForCassandra() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-3", "Aggregate with GROUP BY", "SELECT * FROM events",
                        "SELECT region, count(*) FROM events GROUP BY region")),
                requestFor("cassandra", "SELECT * FROM events"));
        assertEquals(0, out.additionalOptimizations().size(),
                "Cassandra cannot GROUP BY across partitions — filter must apply");
    }

    @Test
    void allowFilteringIsFilteredForCassandra() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-4", "Make it work by adding ALLOW FILTERING",
                        "SELECT * FROM events WHERE region='eu'",
                        "SELECT * FROM events WHERE region='eu' ALLOW FILTERING")),
                requestFor("cassandra", "SELECT * FROM events WHERE region='eu'"));
        assertEquals(0, out.additionalOptimizations().size(),
                "ALLOW FILTERING is the Cassandra anti-pattern — never recommended");
    }

    @Test
    void sqlInsertIsFilteredForRedis() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-5", "Insert via SQL", "SET k v",
                        "INSERT INTO kv (key, value) VALUES ('k', 'v')")),
                requestFor("redis", "SET k v"));
        assertEquals(0, out.additionalOptimizations().size(),
                "SQL DML must not be suggested for Redis");
    }

    @Test
    void mongoStyleSuggestionIsFilteredForRedis() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-6", "Aggregate", "SCAN 0",
                        "db.coll.aggregate([{$match: {}}])")),
                requestFor("redis", "KEYS *"));
        assertEquals(0, out.additionalOptimizations().size(),
                "Mongo aggregation pipeline must not be suggested for Redis");
    }

    @Test
    void sqlGroupByIsFilteredForElasticsearch() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-7", "Use GROUP BY", "{\"query\": {}}",
                        "SELECT category, count(*) FROM products GROUP BY category")),
                requestFor("elasticsearch", "{\"query\": {\"match_all\": {}}}"));
        assertEquals(0, out.additionalOptimizations().size(),
                "GROUP BY is not Elasticsearch Query DSL");
    }

    @Test
    void joinIsFilteredForDynamoDb() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-8", "Join two tables", "SELECT * FROM Orders",
                        "SELECT o.id, u.name FROM Orders o JOIN Users u ON u.id = o.uid")),
                requestFor("dynamodb", "SELECT * FROM Orders"));
        assertEquals(0, out.additionalOptimizations().size(),
                "DynamoDB does not support JOIN — neither in SDK nor PartiQL");
    }

    @Test
    void coalesceStillFilteredForDynamoDb() {
        // Sanity: pre-existing rule for DynamoDB must keep working.
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-9", "Use COALESCE for nulls", "if_not_exists(:v, '')",
                        "COALESCE(attr, 'default')")),
                requestFor("dynamodb", "SET attr = if_not_exists(attr, :v)"));
        assertEquals(0, out.additionalOptimizations().size(),
                "DynamoDB has no COALESCE — pre-existing filter must still apply");
    }

    // ----- Diagnostic-only matcher (DIAGNOSTIC_ONLY) -----

    @Test
    void mongoExplainIsFilteredAsDiagnostic() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-10", "Profile the query", "db.orders.find({})",
                        "db.orders.find({status: 'open'}).explain(\"executionStats\")")),
                requestFor("mongodb", "db.orders.find({status: 'open'})"));
        assertEquals(0, out.additionalOptimizations().size(),
                ".explain() is diagnostic, not an optimization");
    }

    @Test
    void elasticsearchExplainQueryIsFilteredAsDiagnostic() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-11", "Inspect scoring", "GET /idx/_search",
                        "GET /idx/_search?explain=true")),
                requestFor("elasticsearch", "GET /idx/_search"));
        assertEquals(0, out.additionalOptimizations().size(),
                "explain=true is diagnostic, not an optimization");
    }

    @Test
    void cassandraTracingIsFilteredAsDiagnostic() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-12", "Trace it", "SELECT * FROM t WHERE pk=1",
                        "TRACING ON; SELECT * FROM t WHERE pk=1;")),
                requestFor("cassandra", "SELECT * FROM t WHERE pk=1"));
        assertEquals(0, out.additionalOptimizations().size(),
                "TRACING ON is diagnostic, not an optimization");
    }

    @Test
    void dynamoDescribeTableIsFilteredAsDiagnostic() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-13", "Check the table layout",
                        "SELECT * FROM Orders",
                        "aws dynamodb describe-table --table-name Orders")),
                requestFor("dynamodb", "SELECT * FROM Orders"));
        assertEquals(0, out.additionalOptimizations().size(),
                "describe-table is diagnostic, not an optimization");
    }

    @Test
    void sqlExplainStillFilteredAsDiagnostic() {
        // Sanity: pre-existing SQL EXPLAIN filter survives the merge into DIAGNOSTIC_ONLY.
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-14", "Profile", "SELECT * FROM t",
                        "EXPLAIN SELECT * FROM t")),
                requestFor("mysql", "SELECT * FROM t"));
        assertEquals(0, out.additionalOptimizations().size(),
                "EXPLAIN <query> must still be filtered for SQL engines");
    }

    // ----- Folklore fragments (FOLKLORE_FRAGMENTS) -----

    @Test
    void allowFilteringFolkloreIsFilteredAcrossEngines() {
        // Even if the engine-specific JOIN/GROUP BY filter doesn't catch it, the folklore
        // matcher should — for both English and Spanish phrasing.
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-15",
                        "Use ALLOW FILTERING for flexibility",
                        "SELECT * FROM t",
                        "SELECT * FROM t WHERE col=1")),
                requestFor("cassandra", "SELECT * FROM t WHERE col=1"));
        assertEquals(0, out.additionalOptimizations().size(),
                "Folklore description containing 'use ALLOW FILTERING' must be filtered");
    }

    @Test
    void keysCommandFolkloreIsFiltered() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-16",
                        "Use KEYS * to list everything",
                        "SCAN 0 MATCH * COUNT 100",
                        "KEYS *")),
                requestFor("redis", "SCAN 0 MATCH * COUNT 100"));
        assertEquals(0, out.additionalOptimizations().size(),
                "KEYS * is a Redis production-killer — must be filtered as folklore");
    }

    @Test
    void whereOperatorFolkloreIsFilteredForMongo() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-17",
                        "Use $where for complex filters",
                        "db.users.find({age: {$gt: 30}})",
                        "db.users.find({$where: 'this.age > 30'})")),
                requestFor("mongodb", "db.users.find({age: {$gt: 30}})"));
        assertEquals(0, out.additionalOptimizations().size(),
                "$where disables indexes — must be filtered as folklore");
    }

    @Test
    void disableRefreshIntervalFolkloreIsFiltered() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-18",
                        "Disable refresh_interval permanently for speed",
                        "PUT /idx/_settings",
                        "PUT /idx/_settings {\"index\":{\"refresh_interval\":\"-1\"}}")),
                requestFor("elasticsearch", "PUT /idx/_settings"));
        assertEquals(0, out.additionalOptimizations().size(),
                "Disabling refresh_interval permanently is folklore — must be filtered");
    }

    @Test
    void flushallFolkloreIsFiltered() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-19",
                        "Use FLUSHALL to clean up the cache",
                        "DEL key1 key2",
                        "FLUSHALL")),
                requestFor("redis", "DEL key1 key2"));
        assertEquals(0, out.additionalOptimizations().size(),
                "FLUSHALL recommendation is production-killer folklore");
    }

    // ----- A legitimate NoSQL optimization must survive -----

    @Test
    void legitimateMongoOptimizationSurvives() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-20",
                        "Add a compound index on status+createdAt to support this filter+sort.",
                        "db.orders.find({status: 'open'}).sort({createdAt: -1})",
                        "db.orders.createIndex({status: 1, createdAt: -1})")),
                requestFor("mongodb", "db.orders.find({status: 'open'}).sort({createdAt: -1})"));
        assertEquals(1, out.additionalOptimizations().size(),
                "A genuine Mongo createIndex suggestion must NOT be filtered");
        assertTrue(out.additionalOptimizations().get(0).optimizedFragment().contains("createIndex"));
    }

    @Test
    void legitimateRedisOptimizationSurvives() {
        ComplementAnalysisResponse out = ComplementAnalysisSanitizer.sanitize(
                responseWith(opt("AI-OPT-21",
                        "Replace blocking KEYS with non-blocking SCAN.",
                        "KEYS user:*",
                        "SCAN 0 MATCH user:* COUNT 100")),
                requestFor("redis", "KEYS user:*"));
        assertEquals(1, out.additionalOptimizations().size(),
                "Recommending SCAN over KEYS is the textbook fix — must survive");
        assertTrue(out.additionalOptimizations().get(0).optimizedFragment().contains("SCAN"));
    }
}
