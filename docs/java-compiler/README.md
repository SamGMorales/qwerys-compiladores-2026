# Compilador SQL — Migración C++ → Java
## QWERYS | Compiladores 2026 | Universidad Mariano Gálvez

### Equipo
| # | Nombre | Rama Git | Archivos Responsables |
|---|--------|----------|----------------------|
| 1 | Marjorie Samantha Girón | feature/marjorie-giron-arquitectura | TokenType.java, Token.java, Main.java, pom.xml |
| 2 | Juanita Raguex Tzum | feature/juanita-raguex-lexer | Lexer.java |
| 3 | Mercedes Azucena López | feature/mercedes-lopez-parser-ast | CompOperator.java, ASTNode.java, ExpressionNode.java, ConditionNode.java, SelectNode.java, Parser.java |
| 4 | Josué David Morales | feature/josue-morales-semantic | DataType.java, Column.java, Table.java, SymbolTable.java, SemanticAnalyzer.java |
| 5 | Joshua Eduardo García | feature/joshua-garcia-testing | LexerTest.java, ParserTest.java, SemanticTest.java |

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
