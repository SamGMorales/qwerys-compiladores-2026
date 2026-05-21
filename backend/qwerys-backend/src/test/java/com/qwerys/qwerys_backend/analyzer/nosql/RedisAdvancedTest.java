package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.RedisAnalyzer;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis pipelining, MULTI/EXEC, ACL, modules, and cross-script rules (Day 24E+).
 */
class RedisAdvancedTest {

    private final RedisAnalyzer analyzer = new RedisAnalyzer();

    private static Set<String> codes(List<SemanticError> r) {
        return r.stream().map(SemanticError::code).collect(Collectors.toSet());
    }

    @Test
    void basicGet_ok() {
        assertTrue(codes(analyzer.analyze("GET mykey", Locale.ENGLISH)).stream().noneMatch(c -> c.startsWith("RDS-TX")));
    }

    @Test
    void ping_ok() {
        assertFalse(codes(analyzer.analyze("PING", Locale.ENGLISH)).contains("RDS-SYNTAX-001"));
    }

    @Test
    void execWithoutMulti_emitsTx002() {
        assertTrue(codes(analyzer.analyze("EXEC", Locale.ENGLISH)).contains("RDS-TX-002"));
    }

    @Test
    void discardWithoutMulti_emitsTx003() {
        assertTrue(codes(analyzer.analyze("DISCARD", Locale.ENGLISH)).contains("RDS-TX-003"));
    }

    @Test
    void multiWithoutExec_crossScript_emitsTx005() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI\nSET a 1", Locale.ENGLISH);
        assertTrue(codes(cross).contains("RDS-TX-005"));
    }

    @Test
    void multiExec_valid_noTx005() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI\nSET a 1\nEXEC", Locale.ENGLISH);
        assertFalse(codes(cross).contains("RDS-TX-005"));
    }

    @Test
    void watchInsideMulti_emitsTx001() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI\nWATCH k\nSET k 1\nEXEC", Locale.ENGLISH);
        assertTrue(codes(cross).contains("RDS-TX-001"));
    }

    @Test
    void blpopInsideMulti_emitsTx004() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI\nBLPOP q 0\nEXEC", Locale.ENGLISH);
        assertTrue(codes(cross).contains("RDS-TX-004"));
    }

    @Test
    void keysStarInsideMulti_emitsPip001() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI\nKEYS *\nEXEC", Locale.ENGLISH);
        assertTrue(codes(cross).contains("RDS-PIP-001"));
    }

    @Test
    void flushdbInsideMulti_emitsPip001() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("MULTI\nFLUSHDB\nEXEC", Locale.ENGLISH);
        assertTrue(codes(cross).contains("RDS-PIP-001"));
    }

    @Test
    void watchWithoutExec_emitsPip003() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("WATCH foo\nSET foo 1", Locale.ENGLISH);
        assertTrue(codes(cross).contains("RDS-PIP-003"));
    }

    @Test
    void subscribeAndPsubscribe_emitsPub001() {
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly("SUBSCRIBE a\nPSUBSCRIBE b*", Locale.ENGLISH);
        assertTrue(codes(cross).contains("RDS-PUB-001"));
    }

    @Test
    void largePipelineCrossScript_emitsPip002() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1002; i++) {
            sb.append("SET k").append(i).append(" ").append(i).append('\n');
        }
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(sb.toString(), Locale.ENGLISH);
        assertTrue(codes(cross).contains("RDS-PIP-002"));
    }

    @Test
    void multiStatement_semicolonBecomesNewline_stillSeesMultiExec() {
        String raw = "MULTI; SET x 1; SET y 2; EXEC";
        List<SemanticError> cross = analyzer.analyzeCrossScriptOnly(raw, Locale.ENGLISH);
        assertFalse(codes(cross).contains("RDS-TX-005"));
    }

    @Test
    void hmsetDeprecation_emitsHmset001() {
        assertTrue(codes(analyzer.analyze("HMSET h a 1 b 2", Locale.ENGLISH)).contains("RDS-HMSET-001"));
    }

    @Test
    void keysWithoutStar_emitsKeys002Not001() {
        Set<String> c = codes(analyzer.analyze("KEYS user:*", Locale.ENGLISH));
        assertFalse(c.contains("RDS-KEYS-001"));
        assertTrue(c.contains("RDS-KEYS-002"));
    }

    @Test
    void keysStar_emitsKeys001() {
        assertTrue(codes(analyzer.analyze("KEYS *", Locale.ENGLISH)).contains("RDS-KEYS-001"));
    }

    @Test
    void flushdbWithoutConfirm_emitsFlush001() {
        assertTrue(codes(analyzer.analyze("FLUSHDB", Locale.ENGLISH)).contains("RDS-FLUSH-001"));
    }

    @Test
    void flushdbWithConfirm_noFlush001() {
        assertFalse(codes(analyzer.analyze("FLUSHDB CONFIRM", Locale.ENGLISH)).contains("RDS-FLUSH-001"));
    }

    @Test
    void sessionKeyWithoutTtl_emitsSess001() {
        assertTrue(codes(analyzer.analyze("SET user:session:abc value", Locale.ENGLISH)).contains("RDS-SESS-001"));
    }

    @Test
    void sessionKeyWithEx_noSess001() {
        assertFalse(codes(analyzer.analyze("SET user:session:abc value EX 3600", Locale.ENGLISH)).contains("RDS-SESS-001"));
    }

    @Test
    void aclSetUserAllCategories_emitsAcl001() {
        assertTrue(codes(analyzer.analyze("ACL SETUSER risky on +@all ~*", Locale.ENGLISH)).contains("RDS-ACL-001"));
    }

    @Test
    void aclSetUserNoPassword_emitsAcl002() {
        assertTrue(codes(analyzer.analyze("ACL SETUSER bob on +@read ~cache:*", Locale.ENGLISH)).contains("RDS-ACL-002"));
    }

    @Test
    void aclDelUserDefault_emitsAcl003() {
        assertTrue(codes(analyzer.analyze("ACL DELUSER default", Locale.ENGLISH)).contains("RDS-ACL-003"));
    }

    @Test
    void jsonSet_emitsMod001() {
        assertTrue(codes(analyzer.analyze("JSON.SET doc $ 1", Locale.ENGLISH)).contains("RDS-MOD-001"));
    }

    @Test
    void ftSearchWithoutLimit_emitsMod002() {
        assertTrue(codes(analyzer.analyze("FT.SEARCH idx '@field:foo*'", Locale.ENGLISH)).contains("RDS-MOD-002"));
    }

    @Test
    void eval_luaMergedFromNosqlPackage() {
        List<SemanticError> r = analyzer.analyze("EVAL \"return redis.call('PING')\" 0", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> e.code().startsWith("LUA-")));
    }

    @Test
    void xreadgroupMalformed_emitsXStream001() {
        assertTrue(codes(analyzer.analyze("XREADGROUP COUNT 10 STREAMS mystream 0", Locale.ENGLISH)).contains("RDS-XStream-001"));
    }

    @Test
    void xaddMaxlenExact_emitsXStream004() {
        assertTrue(codes(analyzer.analyze("XADD s MAXLEN = 1000 * f1 v1", Locale.ENGLISH)).contains("RDS-XStream-004"));
    }

    @Test
    void geoaddInvalidCoordinate_emitsGeo() {
        List<SemanticError> r = analyzer.analyze("GEOADD cities 200 200 Paris", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> e.code().startsWith("RDS-GEO")));
    }

    @Test
    void scriptFlush_emitsScript001() {
        assertTrue(codes(analyzer.analyze("SCRIPT FLUSH", Locale.ENGLISH)).contains("RDS-Script-001"));
    }

    @Test
    void bitopNotWrongArity_emitsBit003() {
        assertTrue(codes(analyzer.analyze("BITOP NOT destonly", Locale.ENGLISH)).contains("RDS-BIT-003"));
    }
}
