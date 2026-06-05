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
    TokenType.java, Token.java     
    Lexer.java                     
    CompOperator.java, ASTNode.java,
    ExpressionNode.java, ConditionNode.java,
    SelectNode.java, Parser.java   
    DataType.java, Column.java, Table.java,
    SymbolTable.java, SemanticAnalyzer.java 
    Main.java                      
  test/java/com/qwerys/compiler/
    LexerTest.java, ParserTest.java,
    SemanticTest.java              
pom.xml                            
```
