package com.qwerys.qwerys_backend.analyzer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Day 24H — Cassandra DCL: CAS-DCL-001..005, CAS-AUTH-001.
 */
class CqlAnalyzerDclTest {

    private final CqlAnalyzer analyzer = new CqlAnalyzer();

    private static boolean hasCode(List<SemanticError> errs, String code) {
        return errs.stream().anyMatch(e -> code.equals(e.code()));
    }

    @Test
    void createRoleWithoutPassword_emitsCasDcl001() {
        List<SemanticError> out = analyzer.analyze("CREATE ROLE reporting;");
        assertTrue(hasCode(out, "CAS-DCL-001"));
    }

    @Test
    void createRoleWithPassword_noCasDcl001() {
        List<SemanticError> out = analyzer.analyze(
                "CREATE ROLE reporting WITH PASSWORD = 'Str0ng!Enough' AND LOGIN = false;");
        assertTrue(out.stream().noneMatch(e -> "CAS-DCL-001".equals(e.code())));
    }

    @Test
    void createRoleSuperuserTrue_emitsCasDcl003() {
        List<SemanticError> out = analyzer.analyze(
                "CREATE ROLE admin_r WITH PASSWORD = 'Str0ng!Enough' AND SUPERUSER = true;");
        assertTrue(hasCode(out, "CAS-DCL-003"));
    }

    @Test
    void grantAllOnKeyspace_emits002and004() {
        List<SemanticError> out = analyzer.analyze("GRANT ALL ON KEYSPACE cycling TO coach;");
        assertTrue(hasCode(out, "CAS-DCL-002"));
        assertTrue(hasCode(out, "CAS-DCL-004"));
    }

    @Test
    void revokeAllOnKeyspace_emits002and004() {
        List<SemanticError> out = analyzer.analyze("REVOKE ALL ON KEYSPACE cycling FROM coach;");
        assertTrue(hasCode(out, "CAS-DCL-002"));
        assertTrue(hasCode(out, "CAS-DCL-004"));
    }

    @Test
    void grantNonLoginRoleToLoginUser_emitsCasDcl005() {
        String script = """
                CREATE ROLE delegate WITH PASSWORD = 'delegatePw1!' AND LOGIN = false;
                CREATE USER appuser WITH PASSWORD = 'appuserPw1!';
                GRANT delegate TO appuser;
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(hasCode(out, "CAS-DCL-005"), () -> out.stream().map(SemanticError::code).collect(Collectors.toList()).toString());
    }

    @Test
    void weakPassword_emitsCasAuth001() {
        List<SemanticError> out = analyzer.analyze(
                "CREATE ROLE u WITH PASSWORD = 'abc' AND LOGIN = true;");
        assertTrue(hasCode(out, "CAS-AUTH-001"));
    }

    @Test
    void longAlphanumericOnlyPassword_emitsCasAuth001() {
        List<SemanticError> out = analyzer.analyze(
                "CREATE ROLE u WITH PASSWORD = 'abcdefgh' AND LOGIN = true;");
        assertTrue(hasCode(out, "CAS-AUTH-001"));
    }

    @Test
    void listRoles_lexes() {
        List<SemanticError> out = analyzer.analyze("LIST ROLES;");
        assertTrue(out.stream().noneMatch(e -> e.severity() == SemanticError.Severity.ERROR));
    }
}
