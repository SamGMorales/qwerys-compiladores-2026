package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisAnalyzerModuleAclPipeliningTest {

    private final RedisAnalyzer analyzer = new RedisAnalyzer();

    @Test
    void aclSetUserAllCategories_emitsAcl001() {
        List<SemanticError> r = analyzer.analyze("ACL SETUSER risky on +@all ~*", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "RDS-ACL-001".equals(e.code())));
    }

    @Test
    void aclSetUserNoPassword_emitsAcl002() {
        List<SemanticError> r = analyzer.analyze("ACL SETUSER bob on +@read ~cache:*", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "RDS-ACL-002".equals(e.code())));
    }

    @Test
    void aclDelUserDefault_emitsAcl003() {
        List<SemanticError> r = analyzer.analyze("ACL DELUSER default", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "RDS-ACL-003".equals(e.code())));
    }

    @Test
    void jsonSet_emitsMod001() {
        List<SemanticError> r = analyzer.analyze("JSON.SET doc $ 1", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "RDS-MOD-001".equals(e.code())));
    }

    @Test
    void ftSearchWithoutLimit_emitsMod002() {
        List<SemanticError> r = analyzer.analyze("FT.SEARCH idx '@field:foo*'", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "RDS-MOD-002".equals(e.code())));
    }
}
