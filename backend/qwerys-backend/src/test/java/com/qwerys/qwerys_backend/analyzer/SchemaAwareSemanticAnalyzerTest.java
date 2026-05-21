package com.qwerys.qwerys_backend.analyzer;

import com.qwerys.qwerys_backend.adapter.ColumnSchema;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaAwareSemanticAnalyzerTest {

    private static DatabaseSchema sampleSchema() {
        TableSchema users = new TableSchema();
        users.setTableName("users");
        ColumnSchema id = new ColumnSchema("id", "INT", false, true, null);
        ColumnSchema name = new ColumnSchema("name", "VARCHAR(100)", true, false, null);
        users.setColumns(List.of(id, name));

        DatabaseSchema schema = new DatabaseSchema();
        schema.setDbType("mysql");
        schema.setDatabaseName("test");
        schema.setTables(List.of(users));
        return schema;
    }

    @Test
    void missingTable_emitsSch001() {
        String sql = "SELECT id FROM orders WHERE id = 1";
        AstNode ast = parse(sql, SqlDialect.MYSQL);
        SemanticAnalyzer base = new SemanticAnalyzer(ast, sql, Locale.ENGLISH);
        List<SemanticError> findings =
                new SchemaAwareSemanticAnalyzer(base, sampleSchema()).analyze();

        assertTrue(findings.stream().anyMatch(e -> "SCH-001".equals(e.code())
                && e.message().contains("orders")));
    }

    @Test
    void missingColumn_emitsSch002() {
        String sql = "SELECT email FROM users WHERE id = 1";
        AstNode ast = parse(sql, SqlDialect.MYSQL);
        SemanticAnalyzer base = new SemanticAnalyzer(ast, sql, Locale.ENGLISH);
        List<SemanticError> findings =
                new SchemaAwareSemanticAnalyzer(base, sampleSchema()).analyze();

        assertTrue(findings.stream().anyMatch(e -> "SCH-002".equals(e.code())
                && e.message().contains("email")
                && e.message().contains("users")));
    }

    @Test
    void typeMismatch_emitsSch003Warning() {
        String sql = "SELECT id FROM users WHERE id = 'abc'";
        AstNode ast = parse(sql, SqlDialect.MYSQL);
        SemanticAnalyzer base = new SemanticAnalyzer(ast, sql, Locale.ENGLISH);
        List<SemanticError> findings =
                new SchemaAwareSemanticAnalyzer(base, sampleSchema()).analyze();

        assertTrue(findings.stream().anyMatch(e -> "SCH-003".equals(e.code())
                && e.severity() == SemanticError.Severity.WARNING));
    }

    @Test
    void validQuery_hasNoSchemaErrors() {
        String sql = "SELECT id, name FROM users WHERE id = 1";
        AstNode ast = parse(sql, SqlDialect.MYSQL);
        SemanticAnalyzer base = new SemanticAnalyzer(ast, sql, Locale.ENGLISH);
        List<SemanticError> findings =
                new SchemaAwareSemanticAnalyzer(base, sampleSchema()).analyze();

        assertFalse(findings.stream().anyMatch(e -> e.code().startsWith("SCH-")));
    }

    @Test
    void spanishLocale_usesSpanishMessages() {
        String sql = "SELECT x FROM missing_table";
        AstNode ast = parse(sql, SqlDialect.MYSQL);
        SemanticAnalyzer base = new SemanticAnalyzer(ast, sql, Locale.forLanguageTag("es"));
        List<SemanticError> findings =
                new SchemaAwareSemanticAnalyzer(base, sampleSchema()).analyze();

        assertTrue(findings.stream().anyMatch(e -> "SCH-001".equals(e.code())
                && e.message().contains("no existe")));
    }

    private static AstNode parse(String sql, SqlDialect dialect) {
        List<Token> tokens = new SqlLexer(sql, dialect).tokenize();
        return new SqlParser(tokens, dialect).parse();
    }
}
