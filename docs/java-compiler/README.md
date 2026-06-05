# Compilador SQL — Migración C++ → Java
## QWERYS | Compiladores 2026 | Universidad Mariano Gálvez

### Cómo ejecutar
```bash
mvn compile
mvn exec:java -Dexec.mainClass="com.qwerys.compiler.Main"
```

### Cómo correr los tests
```bash
mvn test
```

### Estructura del proyecto
```
src/
  main/java/com/qwerys/compiler/
    TokenType.java, Token.java     ← Miembro 1
    Lexer.java                     ← Miembro 2
    CompOperator.java, ASTNode.java,
    ExpressionNode.java, ConditionNode.java,
    SelectNode.java, Parser.java   ← Miembro 3
    DataType.java, Column.java, Table.java,
    SymbolTable.java, SemanticAnalyzer.java ← Miembro 4
    Main.java                      ← Miembro 1
  test/java/com/qwerys/compiler/
    LexerTest.java, ParserTest.java,
    SemanticTest.java              ← Miembro 5
pom.xml                            ← Miembro 1
```
