package com.qwerys.qwerys_backend.analyzer.nosql;

import com.qwerys.qwerys_backend.analyzer.MongoDbAnalyzer;
import com.qwerys.qwerys_backend.analyzer.MongoDbLexer;
import com.qwerys.qwerys_backend.analyzer.NoSqlTokenType;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fluent cursor chaining ({@code .find().limit()}), common in mongosh.
 */
class MongoDbChainTest {

    private final MongoDbAnalyzer analyzer = new MongoDbAnalyzer();

    private static Set<String> codes(List<SemanticError> r) {
        return r.stream().map(SemanticError::code).collect(Collectors.toSet());
    }

    @Test
    void lexer_findLimitSort_tokenizesChainMethods() {
        var tokens = new MongoDbLexer("db.usuarios.find({ activo: true }).limit(100).sort({ nombre: 1 })", Locale.ENGLISH)
                .tokenize();
        long chains = tokens.stream().filter(t -> t.type() == NoSqlTokenType.CHAIN_METHOD).count();
        assertTrue(chains >= 2);
    }

    @Test
    void analyze_findLimit_noSyntax001() {
        List<SemanticError> r = analyzer.analyze(
                "db.usuarios.find({ activo: true }).limit(100);", Locale.ENGLISH);
        assertFalse(codes(r).contains("MGO-SYNTAX-001"));
    }

    @Test
    void analyze_unknownChainMethod_emitsChain001() {
        List<SemanticError> r = analyzer.analyze(
                "db.usuarios.find({}).notARealChainMethod(1)", Locale.ENGLISH);
        assertTrue(codes(r).contains("MGO-CHAIN-001"));
        assertFalse(codes(r).contains("MGO-SYNTAX-001"));
    }
}
