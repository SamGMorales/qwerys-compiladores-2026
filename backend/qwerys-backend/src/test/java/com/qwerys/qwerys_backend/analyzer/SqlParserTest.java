package com.qwerys.qwerys_backend.analyzer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlParserTest {

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private AstNode parse(String sql) {
        List<Token> tokens = new SqlLexer(sql).tokenize();
        return new SqlParser(tokens).parse();
    }

    // =========================================================================
    // Test 1 – SELECT with WHERE clause parses without errors
    // =========================================================================

    @Test
    void parsesSelectWithWhereClause() {
        AstNode root = parse("SELECT id, name FROM users WHERE id = 1");

        // Root is a SELECT statement
        assertEquals("SELECT_STATEMENT", root.getNodeType());

        // First child: COLUMN_LIST with two columns
        AstNode columnList = root.getChildren().get(0);
        assertEquals("COLUMN_LIST", columnList.getNodeType());
        assertEquals(2, columnList.getChildren().size());

        AstNode firstCol = columnList.getChildren().get(0);
        assertEquals("COLUMN_REF", firstCol.getNodeType());
        assertEquals("id", firstCol.getValue());

        AstNode secondCol = columnList.getChildren().get(1);
        assertEquals("COLUMN_REF", secondCol.getNodeType());
        assertEquals("name", secondCol.getValue());

        // Second child: TABLE_REF
        AstNode tableRef = root.getChildren().get(1);
        assertEquals("TABLE_REF", tableRef.getNodeType());
        assertEquals("users", tableRef.getValue());

        // Third child: WHERE_CLAUSE with a COMPARISON node
        AstNode where = root.getChildren().get(2);
        assertEquals("WHERE_CLAUSE", where.getNodeType());

        AstNode comparison = where.getChildren().get(0);
        assertEquals("COMPARISON", comparison.getNodeType());
        assertEquals("=", comparison.getValue());

        AstNode lhs = comparison.getChildren().get(0);
        assertEquals("COLUMN_REF", lhs.getNodeType());
        assertEquals("id", lhs.getValue());

        AstNode rhs = comparison.getChildren().get(1);
        assertEquals("LITERAL", rhs.getNodeType());
        assertEquals("1", rhs.getValue());

        System.out.println("=== Test 1: SELECT with WHERE ===");
        System.out.println(root);
    }

    // =========================================================================
    // Test 2 – "SELECT FROM users" must raise a ParseException
    //          (column list is missing – FROM is not a valid column expression)
    // =========================================================================

    @Test
    void rejectsSelectMissingColumnList() {
        SqlParser.ParseException ex = assertThrows(
                SqlParser.ParseException.class,
                () -> parse("SELECT FROM users")
        );

        // The error message must mention the unexpected token and its location
        String msg = ex.getMessage();
        assertNotNull(msg, "Exception message must not be null");
        assertFalse(msg.isBlank(), "Exception message must not be blank");

        System.out.println("=== Test 2: Missing column list ===");
        System.out.println("ParseException: " + msg);
    }

    // =========================================================================
    // Test 3 – INSERT INTO … VALUES (…) parses without errors
    // =========================================================================

    @Test
    void parsesInsertStatement() {
        AstNode root = parse("INSERT INTO users (name) VALUES ('Juan')");

        // Root is an INSERT statement
        assertEquals("INSERT_STATEMENT", root.getNodeType());

        // First child: TABLE_REF
        AstNode tableRef = root.getChildren().get(0);
        assertEquals("TABLE_REF", tableRef.getNodeType());
        assertEquals("users", tableRef.getValue());

        // Second child: COLUMN_LIST with the single column "name"
        AstNode columnList = root.getChildren().get(1);
        assertEquals("COLUMN_LIST", columnList.getNodeType());
        assertEquals(1, columnList.getChildren().size());
        assertEquals("name", columnList.getChildren().get(0).getValue());

        // Third child: VALUES with one row containing one literal
        AstNode values = root.getChildren().get(2);
        assertEquals("VALUES", values.getNodeType());
        assertEquals(1, values.getChildren().size());

        AstNode row = values.getChildren().get(0);
        assertEquals("VALUE_ROW", row.getNodeType());
        assertEquals(1, row.getChildren().size());

        AstNode literal = row.getChildren().get(0);
        assertEquals("LITERAL", literal.getNodeType());
        assertEquals("'Juan'", literal.getValue());

        System.out.println("=== Test 3: INSERT INTO … VALUES ===");
        System.out.println(root);
    }

    @Test
    void parsesWhereInWithSelectSubquery() {
        AstNode root = parse("SELECT * FROM orders WHERE customer_id IN (SELECT id FROM customers WHERE active = 1)");

        assertEquals("SELECT_STATEMENT", root.getNodeType());
        AstNode where = root.getChildren().stream()
                .filter(c -> "WHERE_CLAUSE".equals(c.getNodeType()))
                .findFirst()
                .orElseThrow();
        AstNode inExpr = where.getChildren().get(0);
        assertEquals("IN_EXPR", inExpr.getNodeType());
        AstNode inList = inExpr.getChildren().get(1);
        assertEquals("IN_LIST", inList.getNodeType());
        assertEquals(1, inList.getChildren().size());
        AstNode sub = inList.getChildren().get(0);
        assertEquals("SUBQUERY", sub.getNodeType());
        assertEquals("SELECT_STATEMENT", sub.getChildren().get(0).getNodeType());
    }

    @Test
    void parsesWhereInDoubleWrappedSelectSubquery() {
        AstNode root = parse(
                "SELECT * FROM orders WHERE id IN ((SELECT customer_id FROM customers WHERE active = 1))");

        AstNode where = root.getChildren().stream()
                .filter(c -> "WHERE_CLAUSE".equals(c.getNodeType()))
                .findFirst()
                .orElseThrow();
        AstNode inExpr = where.getChildren().get(0);
        assertEquals("IN_EXPR", inExpr.getNodeType());
        AstNode inList = inExpr.getChildren().get(1);
        assertEquals("IN_LIST", inList.getNodeType());
        assertEquals(1, inList.getChildren().size());
        assertEquals("SUBQUERY", inList.getChildren().get(0).getNodeType());
        assertEquals("SELECT_STATEMENT", inList.getChildren().get(0).getChildren().get(0).getNodeType());
    }

    @Test
    void parsesWhereInWithCteSubquery() {
        String sql = """
                SELECT * FROM orders WHERE id IN (
                  WITH active AS (SELECT id FROM customers WHERE active = 1)
                  SELECT customer_id FROM active
                )""";
        AstNode root = parse(sql);
        AstNode where = root.getChildren().stream()
                .filter(c -> "WHERE_CLAUSE".equals(c.getNodeType()))
                .findFirst()
                .orElseThrow();
        AstNode inExpr = where.getChildren().get(0);
        AstNode inList = inExpr.getChildren().get(1);
        AstNode sub = inList.getChildren().get(0);
        assertEquals("SUBQUERY", sub.getNodeType());
        assertEquals("WITH_SELECT_STATEMENT", sub.getChildren().get(0).getNodeType());
    }

    @Test
    void parsesTopLevelWithSelectStatement() {
        AstNode root = parse("""
                WITH cte AS (SELECT id FROM customers WHERE active = 1)
                SELECT * FROM orders WHERE customer_id IN (SELECT id FROM cte)""");
        assertEquals("WITH_SELECT_STATEMENT", root.getNodeType());
        assertNotNull(root.getChildren().stream().filter(c -> "WITH_CLAUSE".equals(c.getNodeType())).findFirst().orElse(null));
        assertNotNull(root.getChildren().stream().filter(c -> "SELECT_STATEMENT".equals(c.getNodeType())).findFirst().orElse(null));
    }
}
