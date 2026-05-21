package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.analyzer.nosql.MongoJsParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoDbAnalyzerSchemaAndJsTest {

    @Test
    void createCollectionThenInsert_missingRequired_emitsMgoSv001() {
        String script = """
                db.createCollection("orders", {
                  validator: {
                    $jsonSchema: {
                      bsonType: "object",
                      required: ["qty"],
                      properties: {
                        qty: { bsonType: "int" }
                      }
                    }
                  }
                });
                db.orders.insertOne({ sku: "a" });
                """;
        MongoDbAnalyzer a = new MongoDbAnalyzer();
        List<SemanticError> r = a.analyze(script, Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "MGO-SV-001".equals(e.code())));
    }

    @Test
    void createCollection_additionalPropertiesFalse_extraField_emitsMgoSv002() {
        String script = """
                db.createCollection("orders", {
                  validator: {
                    $jsonSchema: {
                      bsonType: "object",
                      required: ["qty"],
                      properties: { qty: { bsonType: "int" } },
                      additionalProperties: false
                    }
                  }
                });
                db.orders.insertOne({ qty: 1, extra: "x" });
                """;
        MongoDbAnalyzer a = new MongoDbAnalyzer();
        List<SemanticError> r = a.analyze(script, Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "MGO-SV-002".equals(e.code())));
    }

    @Test
    void createCollection_typeMismatch_emitsMgoSv003() {
        String script = """
                db.createCollection("orders", {
                  validator: {
                    $jsonSchema: {
                      bsonType: "object",
                      required: ["name"],
                      properties: { name: { bsonType: "string" } }
                    }
                  }
                });
                db.orders.insertOne({ name: 42 });
                """;
        MongoDbAnalyzer a = new MongoDbAnalyzer();
        List<SemanticError> r = a.analyze(script, Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "MGO-SV-003".equals(e.code())));
    }

    @Test
    void whereWithEval_emitsMgoJs001and102() {
        String q = "db.users.find({ $where: \"eval(1+1)\" })";
        MongoDbAnalyzer a = new MongoDbAnalyzer();
        List<SemanticError> r = a.analyze(q, Locale.ENGLISH);
        Set<String> codes = r.stream().map(SemanticError::code).collect(Collectors.toSet());
        assertTrue(codes.contains("MGO-JS-001"));
        assertTrue(codes.contains("MGO-JS-102"));
    }

    @Test
    void mongoJsParser_detectsThis() {
        List<SemanticError> r = MongoJsParser.analyze("return this.age > 3", Locale.ENGLISH);
        assertTrue(r.stream().anyMatch(e -> "MGO-JS-101".equals(e.code())));
    }

    @Test
    void analyzeFragment_seesPriorCreateCollectionInFullScript() {
        String full = """
                db.createCollection("cats", {
                  validator: {
                    $jsonSchema: {
                      bsonType: "object",
                      required: ["name"],
                      properties: { name: { bsonType: "string" } }
                    }
                  }
                });
                db.cats.insertOne({ });
                """;
        List<String> stmts = StatementSplitter.split(full, SqlDialect.GENERIC);
        String insertStmt = stmts.get(1).strip();
        MongoDbAnalyzer a = new MongoDbAnalyzer();
        List<SemanticError> r = a.analyzeFragment(insertStmt, Locale.ENGLISH, full);
        assertTrue(r.stream().anyMatch(e -> "MGO-SV-001".equals(e.code())));
    }
}
