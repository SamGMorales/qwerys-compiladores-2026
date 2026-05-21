package com.qwerys.qwerys_backend.analyzer.schema;

import com.qwerys.qwerys_backend.adapter.ColumnSchema;
import com.qwerys.qwerys_backend.adapter.DatabaseSchema;
import com.qwerys.qwerys_backend.adapter.TableSchema;
import com.qwerys.qwerys_backend.analyzer.AstNode;
import com.qwerys.qwerys_backend.analyzer.SemanticError;
import com.qwerys.qwerys_backend.analyzer.SqlDialect;
import com.qwerys.qwerys_backend.analyzer.SqlLexer;
import com.qwerys.qwerys_backend.analyzer.SqlParser;
import com.qwerys.qwerys_backend.analyzer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveSchemaEnrichmentTest {

  private static DatabaseSchema mongoSchema() {
    TableSchema users = new TableSchema();
    users.setTableName("users");
    ColumnSchema name = new ColumnSchema("name", "string", true, false, null);
    ColumnSchema age = new ColumnSchema("age", "number", true, false, null);
    users.setColumns(List.of(name, age));

    DatabaseSchema schema = new DatabaseSchema();
    schema.setDbType("mongodb");
    schema.setTables(List.of(users));
    return schema;
  }

  @Test
  void mongo_missingCollection_emitsSch001() {
    String q = "db.orders.find({ name: \"x\" })";
    List<SemanticError> out = new java.util.ArrayList<>();
    MongoLiveSchemaValidator.validate(q, mongoSchema(), Locale.ENGLISH, out);
    assertTrue(out.stream().anyMatch(e -> "SCH-001".equals(e.code()) && e.message().contains("orders")));
  }

  @Test
  void mongo_missingField_emitsSch002() {
    String q = "db.users.find({ email: \"x\" })";
    List<SemanticError> out = new java.util.ArrayList<>();
    MongoLiveSchemaValidator.validate(q, mongoSchema(), Locale.ENGLISH, out);
    assertTrue(out.stream().anyMatch(e -> "SCH-002".equals(e.code()) && e.message().contains("email")));
  }

  @Test
  void partiQl_missingColumn_emitsSch002() {
    String sql = "SELECT email FROM users WHERE id = 1";
    List<Token> tokens = new SqlLexer(sql, SqlDialect.GENERIC).tokenize();
    AstNode ast = new SqlParser(tokens, SqlDialect.GENERIC).parse();

    TableSchema users = new TableSchema();
    users.setTableName("users");
    users.setColumns(List.of(new ColumnSchema("id", "INT", false, true, null)));

    DatabaseSchema schema = new DatabaseSchema();
    schema.setTables(List.of(users));

    List<SemanticError> out = new java.util.ArrayList<>();
    AstLiveSchemaValidator.validate(ast, schema, Locale.ENGLISH, SchemaEntityLabels.DYNAMODB, out);
    assertTrue(out.stream().anyMatch(e -> "SCH-002".equals(e.code())));
  }

  @Test
  void mongo_validQuery_noSchemaErrors() {
    String q = "db.users.find({ name: \"Ann\" })";
    List<SemanticError> out = new java.util.ArrayList<>();
    MongoLiveSchemaValidator.validate(q, mongoSchema(), Locale.ENGLISH, out);
    assertFalse(out.stream().anyMatch(e -> e.code().startsWith("SCH-")));
  }

  @Test
  void dynamoTransact_missingTable_emitsSch001() {
    String json = """
        {"TransactItems":[{"Put":{"TableName":"missing","Item":{"id":{"S":"1"}}}}]}
        """;
    DatabaseSchema schema = new DatabaseSchema();
    TableSchema users = new TableSchema();
    users.setTableName("users");
    users.setColumns(List.of(new ColumnSchema("id", "string", false, true, null)));
    schema.setTables(List.of(users));

    List<SemanticError> out = new java.util.ArrayList<>();
    DynamoDbLiveSchemaValidator.validateTransactJson(json, schema, Locale.ENGLISH, out);
    assertTrue(out.stream().anyMatch(e -> "SCH-001".equals(e.code())));
  }

  @Test
  void elasticsearch_rangeTypeMismatch_emitsSch003() {
    TableSchema idx = new TableSchema();
    idx.setTableName("logs");
    ColumnSchema age = new ColumnSchema("age", "long", true, false, null);
    idx.setColumns(List.of(age));
    DatabaseSchema schema = new DatabaseSchema();
    schema.setTables(List.of(idx));

    String q = """
        {"query":{"range":{"age":{"gte":"not-a-number"}}}}
        """;
    List<SemanticError> out = new java.util.ArrayList<>();
    ElasticsearchLiveSchemaValidator.validate(q, "logs", schema, Locale.ENGLISH, out);
    assertTrue(out.stream().anyMatch(e -> "SCH-003".equals(e.code())));
  }
}
