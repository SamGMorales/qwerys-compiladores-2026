package com.qwerys.qwerys_backend.optimization;

import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlLexer;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.model.OptimizationSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptimizationEngineSqlServerPaginationTest {

    @Test
    void opt003_sqlServer_suggestsTopNotIdenticalToOriginal() throws Exception {
        String query = "SELECT * FROM users";
        List<com.qwerys.qwerys_backend.analyzer.Token> tokens =
                new SqlLexer(query, SqlDialect.SQLSERVER).tokenize();
        AstNode ast = new SqlParser(tokens, SqlDialect.SQLSERVER).parse();

        OptimizationEngine engine = new OptimizationEngine();
        List<OptimizationSuggestion> suggestions = engine.optimize(ast, query, SqlDialect.SQLSERVER);

        OptimizationSuggestion opt003 = suggestions.stream()
                .filter(s -> "OPT-003".equals(s.ruleId()))
                .findFirst()
                .orElseThrow();

        assertTrue(opt003.optimizedFragment().toUpperCase().contains("TOP 100"));
        assertNotEquals(
                opt003.originalFragment().strip(),
                opt003.optimizedFragment().strip());
    }
}
