package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlLexer;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimizationEngineSchemaColumnsTest {

    private static final List<String> USER_COLUMNS = List.of("id", "nombre", "email");

    @Test
    void opt001_withoutSchema_usesPlaceholderColumns() throws Exception {
        String query = "SELECT * FROM users";
        AstNode ast = parse(query, SqlDialect.MYSQL);

        OptimizationSuggestion opt001 = findRule(new OptimizationEngine().optimize(ast, query, SqlDialect.MYSQL), "OPT-001");

        assertTrue(opt001.optimizedFragment().contains("col1, col2, col3"));
        assertFalse(opt001.optimizedFragment().contains("nombre"));
    }

    @Test
    void opt001_withSchemaColumns_usesRealColumnNames() throws Exception {
        String query = "SELECT * FROM users";
        AstNode ast = parse(query, SqlDialect.MYSQL);

        OptimizationSuggestion opt001 = findRule(
                new OptimizationEngine().optimize(ast, query, SqlDialect.MYSQL, USER_COLUMNS),
                "OPT-001");

        assertTrue(opt001.optimizedFragment().contains("id, nombre, email"));
        assertFalse(opt001.optimizedFragment().contains("col1"));
    }

    @Test
    void opt003_withSchemaColumns_expandsStarAndAddsLimit() throws Exception {
        String query = "SELECT * FROM users";
        AstNode ast = parse(query, SqlDialect.MYSQL);

        OptimizationSuggestion opt003 = findRule(
                new OptimizationEngine().optimize(ast, query, SqlDialect.MYSQL, USER_COLUMNS),
                "OPT-003");

        assertTrue(opt003.optimizedFragment().contains("SELECT id, nombre, email FROM users"));
        assertTrue(opt003.optimizedFragment().contains("LIMIT 100"));
        assertFalse(opt003.optimizedFragment().contains("SELECT *"));
    }

    @Test
    void opt003_sqlServer_withSchemaColumns_usesTopAndRealColumns() throws Exception {
        String query = "SELECT * FROM users";
        AstNode ast = parse(query, SqlDialect.SQLSERVER);

        OptimizationSuggestion opt003 = findRule(
                new OptimizationEngine().optimize(ast, query, SqlDialect.SQLSERVER, USER_COLUMNS),
                "OPT-003");

        assertTrue(opt003.optimizedFragment().toUpperCase().contains("TOP 100"));
        assertTrue(opt003.optimizedFragment().contains("id, nombre, email"));
        assertFalse(opt003.optimizedFragment().contains("SELECT *"));
    }

    @Test
    void expandSelectStar_replacesDistinctStar() {
        String expanded = SchemaOptimizationSupport.expandSelectStar(
                "SELECT DISTINCT * FROM users", USER_COLUMNS);
        assertTrue(expanded.contains("SELECT DISTINCT id, nombre, email"));
    }

    private static OptimizationSuggestion findRule(List<OptimizationSuggestion> suggestions, String ruleId) {
        return suggestions.stream()
                .filter(s -> ruleId.equals(s.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing rule " + ruleId));
    }

    private static AstNode parse(String query, SqlDialect dialect) throws Exception {
        return new SqlParser(new SqlLexer(query, dialect).tokenize(), dialect).parse();
    }
}
