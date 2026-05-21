package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Day 24G — Cassandra UDF, UDA, and trigger rules (CAS-UDF-*, CAS-UDA-*, CAS-TRG-*).
 */
class CqlAnalyzerUdfUdaTriggerTest {

    private final CqlAnalyzer analyzer = new CqlAnalyzer();

    private static List<String> codes(List<SemanticError> e) {
        return e.stream().map(SemanticError::code).collect(Collectors.toList());
    }

    @Test
    void casUdf001_noDeterministic_info() {
        String cql = """
                CREATE FUNCTION ks.plus (a int, b int)
                CALLED ON NULL INPUT
                RETURNS int
                LANGUAGE java
                AS 'return a+b;';
                """;
        List<SemanticError> out = analyzer.analyze(cql, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-UDF-001"));
    }

    @Test
    void casUdf002_manyLoops_warning() {
        String body = "var x=0; for(var i=0;i<10;i++){ for(var j=0;j<10;j++){ while(x<3){ x++; } } } return x;";
        String cql = "CREATE FUNCTION ks.slow (a int) CALLED ON NULL INPUT RETURNS int LANGUAGE javascript AS '" + body + "';";
        List<SemanticError> out = analyzer.analyze(cql, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-UDF-002"));
    }

    @Test
    void casUdf003_javaIo_error() {
        String cql = """
                CREATE FUNCTION ks.bad (a int) CALLED ON NULL INPUT RETURNS int LANGUAGE java
                AS 'return java.io.File.separator.length();';
                """;
        List<SemanticError> out = analyzer.analyze(cql, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-UDF-003"));
    }

    @Test
    void casUda001_noInitcond_warning() {
        String script = """
                CREATE FUNCTION ks.f (a int, b int) CALLED ON NULL INPUT RETURNS int LANGUAGE java AS 'return a+b;';
                CREATE AGGREGATE ks.myagg (int) SFUNC ks.f STYPE int;
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-UDA-001"));
    }

    @Test
    void casUda002_missingSfunc_error() {
        String cql = """
                CREATE AGGREGATE ks.myagg (int) SFUNC ks.does_not_exist STYPE int INITCOND 0;
                """;
        List<SemanticError> out = analyzer.analyze(cql, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-UDA-002"));
    }

    @Test
    void casUda002_sfuncResolved_ok() {
        String script = """
                CREATE FUNCTION ks.f (a int, b int) CALLED ON NULL INPUT RETURNS int LANGUAGE java AS 'return a+b;';
                CREATE AGGREGATE ks.myagg (int) SFUNC ks.f STYPE int INITCOND 0;
                """;
        List<SemanticError> out = analyzer.analyze(script, Locale.ENGLISH);
        assertTrue(codes(out).stream().noneMatch("CAS-UDA-002"::equals));
    }

    @Test
    void casTrg001_alwaysWarning() {
        String cql = "CREATE TRIGGER tr1 ON ks.users USING 'com.example.Trig';";
        List<SemanticError> out = analyzer.analyze(cql, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-TRG-001"));
    }

    @Test
    void casTrg002_systemTable_error() {
        String cql = "CREATE TRIGGER tr1 ON system_schema.tables USING 'com.example.Trig';";
        List<SemanticError> out = analyzer.analyze(cql, Locale.ENGLISH);
        assertTrue(codes(out).contains("CAS-TRG-002"));
    }
}
