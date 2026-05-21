package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.MongoDbAnalyzer;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.StatementSplitter;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MongoDB transactions, change streams, and multi-statement shell scripts (Day 24B+).
 */
class MongoTransactionsTest {

    private final MongoDbAnalyzer analyzer = new MongoDbAnalyzer();

    private static Set<String> codes(List<SemanticError> r) {
        return r.stream().map(SemanticError::code).collect(Collectors.toSet());
    }

    @Test
    void basicFind_noTransactionHints() {
        List<SemanticError> r = analyzer.analyze("db.users.find({ active: true })", Locale.ENGLISH);
        assertFalse(codes(r).contains("MGO-TX-001"));
        assertFalse(codes(r).contains("MGO-TX-OUT-001"));
    }

    @Test
    void startTransaction_withoutCommitOrAbort_emitsTx001() {
        String s = "session.startTransaction()";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-001"));
    }

    @Test
    void startTransaction_withCommit_noTx001() {
        String s = "session.startTransaction();\nsession.commitTransaction();";
        assertFalse(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-001"));
    }

    @Test
    void startTransaction_withAbort_noTx001() {
        String s = "session.startTransaction();\nsession.abortTransaction();";
        assertFalse(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-001"));
    }

    @Test
    void dbWriteBesideManualStart_emitsTx002() {
        String s = """
                session.startTransaction();
                db.orders.insertOne({ a: 1 });
                session.commitTransaction();
                """;
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-002"));
    }

    @Test
    void withTransactionPresent_suppressesTx002Pattern() {
        String s = "session.withTransaction(() => { db.orders.insertOne({}); });";
        assertFalse(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-002"));
    }

    @Test
    void longMaxCommitTimeMs_emitsTx003() {
        String s = "session.startTransaction({ maxCommitTimeMS: 120000 });\nsession.commitTransaction();";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-003"));
    }

    @Test
    void startTransaction_stringLiteralArg_emitsTx004() {
        String s = "session.startTransaction('local');";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-004"));
    }

    @Test
    void startTransaction_arrayLiteralArg_emitsTx004() {
        String s = "session.startTransaction([ 'x' ]);";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-004"));
    }

    @Test
    void startTransaction_validOptions_noTx004() {
        String s = "session.startTransaction({ readConcern: { level: 'local' } });";
        assertFalse(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-004"));
    }

    @Test
    void watch_emitsCs001() {
        String s = "db.coll.watch()";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-CS-001"));
    }

    @Test
    void watch_emptyPipeline_emitsCs002() {
        String s = "db.coll.watch()";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-CS-002"));
    }

    @Test
    void watch_withMatchPipeline_noCs002() {
        String s = "db.coll.watch([{ $match: { op: 'insert' } }])";
        Set<String> c = codes(analyzer.analyze(s, Locale.ENGLISH));
        assertTrue(c.contains("MGO-CS-001"));
        assertFalse(c.contains("MGO-CS-002"));
    }

    @Test
    void resumeAfterNull_emitsCs003() {
        String s = "db.coll.watch([], { resumeAfter: null })";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-CS-003"));
    }

    @Test
    void resumeAfterEmptyObject_emitsCs003() {
        String s = "db.coll.watch([], { resumeAfter: { } })";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-CS-003"));
    }

    @Test
    void fullDocumentUpdateLookup_emitsCs004() {
        String s = "db.coll.watch([], { fullDocument: 'updateLookup' })";
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-CS-004"));
    }

    @Test
    void aggregateOutInsideTransactionHint_emitsTxOut001() {
        String s = """
                session.startTransaction();
                db.orders.aggregate([{ $match: { a: 1 } }, { $out: 'archive' }]);
                """;
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-OUT-001"));
    }

    @Test
    void aggregateMergeInsideTransactionHint_emitsTxOut001() {
        String s = """
                session.startTransaction();
                db.orders.aggregate([{ $merge: { into: 'summary' } }]);
                """;
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-OUT-001"));
    }

    @Test
    void aggregateOutWithoutTransaction_noTxOut001() {
        String s = "db.orders.aggregate([{ $out: 'archive' }])";
        assertFalse(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-OUT-001"));
    }

    @Test
    void manyStagesWithoutEarlyMatch_emitsAggPerf001() {
        StringBuilder pipe = new StringBuilder("db.big.aggregate([");
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                pipe.append(", ");
            }
            pipe.append("{ $project: { x").append(i).append(": 1 } }");
        }
        pipe.append("])");
        assertTrue(codes(analyzer.analyze(pipe.toString(), Locale.ENGLISH)).contains("MGO-AGG-PERF-001"));
    }

    @Test
    void manyStagesWithEarlyMatch_noAggPerf001() {
        StringBuilder pipe = new StringBuilder("db.big.aggregate([{ $match: { ok: true } }");
        for (int i = 0; i < 6; i++) {
            pipe.append(", { $project: { x").append(i).append(": 1 } }");
        }
        pipe.append("])");
        assertFalse(codes(analyzer.analyze(pipe.toString(), Locale.ENGLISH)).contains("MGO-AGG-PERF-001"));
    }

    @Test
    void multiStatement_twoFinds_splitBySemicolon() {
        String script = "db.a.find({}); db.b.find({ x: 1 })";
        List<String> parts = StatementSplitter.split(script, SqlDialect.GENERIC);
        assertTrue(parts.size() >= 2);
        assertTrue(codes(analyzer.analyze(parts.get(0), Locale.ENGLISH)).stream().noneMatch(c -> c.startsWith("MGO-TX")));
        assertTrue(codes(analyzer.analyze(parts.get(1), Locale.ENGLISH)).stream().noneMatch(c -> c.startsWith("MGO-TX")));
    }

    @Test
    void multiStatement_txThenWatch_crossFragmentContext() {
        String full = """
                session.startTransaction();
                db.logs.insertOne({ v: 1 });
                session.commitTransaction();
                db.events.watch([{ $match: { fullDocument: { $exists: true } } }]);
                """;
        List<String> stmts = StatementSplitter.split(full, SqlDialect.GENERIC);
        assertTrue(stmts.size() >= 2);
        List<SemanticError> last = analyzer.analyze(stmts.get(stmts.size() - 1), Locale.ENGLISH);
        assertTrue(codes(last).contains("MGO-CS-001"));
    }

    @Test
    void deeplyNestedIf_luaStyleIdentifiers_stillParsesShell() {
        StringBuilder sb = new StringBuilder("db.x.find({ ");
        for (int d = 0; d < 12; d++) {
            sb.append("l").append(d).append(": { ");
        }
        sb.append("v: 1");
        for (int d = 0; d < 12; d++) {
            sb.append(" }");
        }
        sb.append(" })");
        List<SemanticError> r = analyzer.analyze(sb.toString(), Locale.ENGLISH);
        assertFalse(codes(r).contains("MGO-SYNTAX-001"));
    }

    @Test
    void highTimeoutMSNearTransaction_emitsTx003() {
        String s = """
                session.startTransaction();
                const opts = { timeoutMS: 90000 };
                db.foo.find({}, null, opts);
                session.commitTransaction();
                """;
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-003"));
    }

    @Test
    void changeStreamResumeAfterWithIdShape_noCs003() {
        String s = "db.coll.watch([], { resumeAfter: { _data: 'opaque', _id: { x: 1 } } })";
        assertFalse(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-CS-003"));
    }

    @Test
    void multiStagePipeline_validLookup() {
        String s = """
                db.orders.aggregate([
                  { $match: { region: 'EU' } },
                  { $lookup: { from: 'customers', localField: 'cid', foreignField: '_id', as: 'c' } },
                  { $unwind: '$c' },
                  { $project: { total: 1, name: '$c.name' } }
                ])
                """;
        assertFalse(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-LOOKUP-001"));
    }

    @Test
    void largeScript_manyWatchCalls_eachEmitsCs001() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            sb.append("db.c").append(i).append(".watch([{ $match: { _id: { $exists: true } } }]);\n");
        }
        List<SemanticError> r = analyzer.analyze(sb.toString(), Locale.ENGLISH);
        long cs1 = r.stream().filter(e -> "MGO-CS-001".equals(e.code())).count();
        assertTrue(cs1 >= 15);
    }

    @Test
    void transactionAndChangeStream_sameScript_tx001StillDetected() {
        String s = """
                session.startTransaction();
                db.x.watch();
                """;
        assertTrue(codes(analyzer.analyze(s, Locale.ENGLISH)).contains("MGO-TX-001"));
    }
}
