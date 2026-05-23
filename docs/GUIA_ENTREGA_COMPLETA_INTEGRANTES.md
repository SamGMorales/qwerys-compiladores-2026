# Guía de entrega completa por integrante — repo del profesor

**Universidad Mariano Gálvez · Compiladores · Ing. Richard Ortiz · Ciclo 2026 Sección A**  
**Entrega:** sábado 23 de mayo de 2026, 23:59

> **Documento fusionado (guía principal).** Une la guía académica + monorepo QWERYS (backend, frontend, Docker).
> **Referencia académica (NO borrar):** [`GUIA_INTEGRANTES_DEFINITIVA.md`](GUIA_INTEGRANTES_DEFINITIVA.md)

| Documento | Uso |
|-----------|-----|
| **Este archivo** | Entrega completa: producto + académico + prompts IA |
| [`GUIA_INTEGRANTES_DEFINITIVA.md`](GUIA_INTEGRANTES_DEFINITIVA.md) | Referencia académica |
| [`ENTREGA_REPO_PROFESOR.md`](ENTREGA_REPO_PROFESOR.md) | Detalle extendido |
| [`../GUIA_EQUIPO.md`](../GUIA_EQUIPO.md) | Correr la app del equipo |

---

## 0. Meta real de la entrega

Al terminar los **5 PR mergeados** en `Azucena17/REFACTORIZACION-_C-_-JAVA`:

```powershell
git clone https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
copy .env.example .env
docker compose up --build
```

- **http://localhost** — misma app que [qwerys-compiladores-2026](https://github.com/SamGMorales/qwerys-compiladores-2026)
- `cd docs/java-compiler && mvn test` — compilador académico OK
- GitHub → Insights → Contributors — **5 integrantes**

Estructura final:

```
REFACTORIZACION-_C-_-JAVA/
??? backend/qwerys-backend/
??? frontend/qwerys-frontend/
??? docs/java-compiler/
??? docker-compose.yml
??? .env.example
??? README.md
??? GUIA_EQUIPO.md
??? .gitignore
```

---

## 1. Qué pasa con el PR #1 de Marjorie (NO borrar)

**No fue por nada. No hay que borrarlo.**

| Situación | Qué hacer |
|-----------|-----------|
| PR aún no mergeado | Ampliar PR o abrir **PR #1b** con monorepo + `docs/java-compiler/` |
| PR ya mergeado | **PR #1b**: mover académico a `docs/java-compiler/` + backend/frontend/Docker |
| Solo en rama local | Reorganizar antes del push |

Correcciones en repo del equipo (`git pull`): `pom.xml` línea 25 `<!--`; `.java` con `!` sin `\\!`.

---

## 2. Reglas Git

1. Fork y PR desde **tu cuenta** GitHub
2. `git config` con tu nombre y email
3. Orden merge: Marjorie → Juanita → Mercedes → Josué → Joshua
4. Sin push a `main`; sin `.env`/API keys; sin Swagger/WebSocket/13 injection/JaCoCo 75%

---

## 3. Repositorios

| Repo | URL |
|------|-----|
| Equipo | https://github.com/SamGMorales/qwerys-compiladores-2026 |
| Profesor | https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA |
| C++ | https://github.com/compilations-teams/compilador-sql-final |

---

## 4. Equipo y ramas

| # | Integrante | Carné | Rama |
|---|------------|-------|------|
| 1 | Marjorie Girón Morales | 1890-22-19957 | `feature/marjorie-giron-arquitectura` |
| 2 | Juanita Raguex Tzum | 1890-20-544 | `feature/juanita-raguex-lexer` |
| 3 | Mercedes López Pérez | 1890-20-11489 | `feature/mercedes-lopez-parser-ast` |
| 4 | Josué Morales Ramírez | 1890-23-10545 | `feature/josue-morales-semantic` |
| 5 | Joshua García Reyes | 1890-22-5831 | `feature/joshua-garcia-testing` |

---

## 5. Plan Gantt vs. código real

| Plan | Real |
|------|------|
| 13 injection | 5 (SE007) |
| 10 reglas | 18 reglas |
| WebSocket | REST POST /api/queries/analyze |
| Swagger | No |
| ASTBuilder | No |

---

## 6. PASO 0 — Entorno

PowerShell. Git, Java 17, Node 20, Docker, Maven.

```powershell
git config --global user.name "Tu Nombre Completo"
git config --global user.email "tu-email-de-github@ejemplo.com"
```

---

## 7. PASO 1 — Fork y variables

```powershell
cd $HOME\Documents
git clone https://github.com/TU-USUARIO/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA

$REF  = "C:\Users\TU_USUARIO\OneDrive\Documentos\qwerys-project"
$FORK = "$HOME\Documents\REFACTORIZACION-_C-_-JAVA"
$BE   = "$REF\backend\qwerys-backend"
$FE   = "$REF\frontend\qwerys-frontend"
$ACAD = "$REF\docs\java-compiler"
cd $REF; git pull origin main
cd $FORK; git checkout -b feature/TU-RAMA-EXACTA
```

---

## 8. PASO 2 — Qué sube CADA integrante (producto + académico)

Copia solo tus archivos. Revisa `git status`. No `git add .` a ciegas.

### PR #1 — Marjorie Girón (Arquitecto)

#### A) Producto — raíz monorepo

```powershell
Copy-Item "$REF\docker-compose.yml" "$FORK\" -Force
Copy-Item "$REF\.env.example" "$FORK\" -Force
Copy-Item "$REF\README.md" "$FORK\" -Force
Copy-Item "$REF\GUIA_EQUIPO.md" "$FORK\" -Force
Copy-Item "$REF\.gitignore" "$FORK\" -Force
Copy-Item "$FE" "$FORK\frontend\qwerys-frontend" -Recurse -Force
Copy-Item "$BE" "$FORK\backend\qwerys-backend" -Recurse -Force
$AN = "$FORK\backend\qwerys-backend\src\main\java\com\qwerys\qwerys_backend\analyzer"
Remove-Item "$AN\SqlLexer.java","$AN\Token.java","$AN\TokenType.java","$AN\StatementSplitter.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\SqlParser.java","$AN\AstNode.java","$AN\SemanticError.java","$AN\SqlDialect.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\SemanticAnalyzer.java","$AN\SchemaAwareSemanticAnalyzer.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\schema" -Recurse -ErrorAction SilentlyContinue
Remove-Item "$FORK\backend\qwerys-backend\src\test" -Recurse -ErrorAction SilentlyContinue
```

#### B) Académico — Fase 1

```powershell
New-Item -ItemType Directory -Force -Path "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler"
Copy-Item "$ACAD\pom.xml" "$FORK\docs\java-compiler\" -Force
Copy-Item "$ACAD\README.md" "$FORK\docs\java-compiler\" -Force
Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\TokenType.java" "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\Token.java" "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\Main.java" "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler\" -Force
```

#### C) Estudiar

| Archivo | Qué dominar |
|---------|-------------|
| `QueryAnalysisService.java` | Orquestación |
| `OptimizationEngine.java` | 18 reglas |
| `docker-compose.yml` | MySQL, PostgreSQL, nginx |

#### D) Prompt tutor IA (copiar y pegar)

```
Soy Marjorie, arquitecta del proyecto QWERYS (migración compilador SQL C++ → Java, Spring Boot + Angular).
Debo dominar en 2 días: (1) cómo QueryAnalysisService orquesta lexer→parser→semántica→optimización,
(2) cómo docker-compose conecta MySQL/PostgreSQL/backend/frontend, (3) cómo el frontend llama /api/queries/analyze.

Contexto: el repo académico usa docs/java-compiler/ (Lexer, Parser, SemanticAnalyzer standalone).
El producto real está en backend/qwerys-backend con SqlLexer, SqlParser, SemanticAnalyzer.

Explícame como si yo hubiera diseñado todo: flujo de una query "SELECT id FROM users WHERE id=1" desde
el POST HTTP hasta la respuesta JSON. Usa diagramas ASCII. Luego hazme 10 preguntas de examen oral
y corrige mis respuestas. Si me equivoco, cítame clases concretas.
```


#### E) Commit y push

```powershell
cd "$FORK\docs\java-compiler"; mvn compile
cd $FORK
git add docker-compose.yml .env.example README.md GUIA_EQUIPO.md .gitignore frontend/ backend/ docs/java-compiler/
git commit -m "feat(arquitectura): monorepo QWERYS + académico Fase 1 - Marjorie Girón"
git push -u origin feature/marjorie-giron-arquitectura
```

---

### PR #2 — Juanita Raguex (Léxico)

Esperar merge PR #1.

#### A) Académico

`docs/java-compiler/.../Lexer.java`

#### B) Producto

`SqlLexer.java`, `Token.java`, `TokenType.java`, `StatementSplitter.java` en `backend/.../analyzer/`

#### C) Prompt tutor IA

```
Soy Juanita, responsable del analizador léxico en QWERYS (curso Compiladores UMG).
Debo explicar como si yo hubiera escrito Lexer.java migrando Lexer.cpp de C++.

Tareas:
1. Explícame fase léxica: entrada SQL → lista de tokens con line/column.
2. Compara Lexer.cpp (solo SELECT/FROM/WHERE) vs SqlLexer.java (SQL completo multi-dialecto).
3. Dame 5 ejemplos de SQL y genera la tabla de tokens esperada.
4. Pregúntame qué pasa con: 'O''Brien', -- comentario, SELECT * (tres tokens distintos).
5. Simula preguntas del profesor sobre expresiones regulares y autómatas finitos aplicados a nuestro lexer.

Responde en español, técnico pero claro. Corrige mis errores.
```


#### D) PowerShell

```powershell
Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\Lexer.java" "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler\" -Force
$AN="$FORK\backend\qwerys-backend\src\main\java\com\qwerys\qwerys_backend\analyzer"
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SqlLexer.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\Token.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\TokenType.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\StatementSplitter.java" $AN -Force
git add docs/java-compiler/src/main/java/com/qwerys/compiler/Lexer.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/SqlLexer.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/Token.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/TokenType.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/StatementSplitter.java
git commit -m "feat(lexer): Lexer académico + SqlLexer - Juanita Raguex"
git push -u origin feature/juanita-raguex-lexer
```

---

### PR #3 — Mercedes López (Parser + AST)

Esperar merge PR #2.

#### A) Académico

`CompOperator.java`, `ASTNode.java`, `ExpressionNode.java`, `ConditionNode.java`, `SelectNode.java`, `Parser.java`

> `ASTBuilder.java` **no existe**.

#### B) Producto

`SqlParser.java`, `AstNode.java`, `SemanticError.java`, `SqlDialect.java`

#### C) Estudiar

| Archivo | Qué dominar |
|---------|-------------|
| `Parser.java` académico | Parser recursivo |
| `SqlParser.java` | Parser SQL completo |
| Repo C++ `Parser.cpp` | Gramática SELECT |

#### D) Prompt tutor IA

```
Soy Mercedes, responsable del analizador sintáctico y AST en QWERYS.
Migré Parser.cpp a Parser.java (recursivo descendente). No usamos ASTBuilder separado.

Enséñame en 2 días:
1. Diferencia análisis léxico vs sintáctico con ejemplo "SELECT a, b FROM t WHERE x = 1".
2. Cómo Parser.java construye SelectNode/ConditionNode desde tokens.
3. Qué es ParseException y por qué reportamos línea/columna.
4. Comparar gramática C++ (solo SELECT simple) vs SqlParser.java (JOIN, subqueries).
5. Hazme dibujar el AST de: SELECT id, name FROM users WHERE id = 1 AND active = 1

Modo examen oral: pregúntame, corrige, repite hasta que domine.
```


#### E) PowerShell

```powershell
$COMP="$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler"
New-Item -ItemType Directory -Force -Path $COMP | Out-Null
foreach ($f in "CompOperator.java","ASTNode.java","ExpressionNode.java","ConditionNode.java","SelectNode.java","Parser.java") {
  Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\$f" $COMP -Force
}
$AN="$FORK\backend\qwerys-backend\src\main\java\com\qwerys\qwerys_backend\analyzer"
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SqlParser.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\AstNode.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SemanticError.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SqlDialect.java" $AN -Force
git add docs/java-compiler/src/main/java/com/qwerys/compiler/*.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/SqlParser.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/AstNode.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/SemanticError.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/SqlDialect.java
git commit -m "feat(parser): Parser/AST académico + SqlParser - Mercedes López"
git push -u origin feature/mercedes-lopez-parser-ast
```

---

### PR #4 — Josué Morales (Semántica)

Esperar merge PR #3.

#### A) Académico

`DataType.java`, `Column.java`, `Table.java`, `SymbolTable.java`, `SemanticAnalyzer.java`

> Sin `IntermediateCodeGenerator.java`. 5 patrones injection (SE007), no 13.

#### B) Producto

`SemanticAnalyzer.java`, `SchemaAwareSemanticAnalyzer.java`, carpeta `schema/`

#### C) Estudiar

| Archivo | Qué dominar |
|---------|-------------|
| `SymbolTable.java` | Tablas usuarios/productos |
| `SemanticAnalyzer.java` | 5 patrones → SE007 |
| `schema/*` | Validación contra BD |

#### D) Prompt tutor IA

```
Soy Josué, responsable del analizador semántico y tabla de símbolos en QWERYS.

Debo dominar:
1. SymbolTable.java (HashMap, tablas hardcodeadas) vs schema dinámico en QWERYS.
2. Validación de tipos en WHERE (INT vs VARCHAR error en C++ y Java).
3. Detección SQL injection: 5 patrones en SemanticAnalyzer (SE007) — no son 13 como decía el plan.
4. Por qué no implementamos IntermediateCodeGenerator y qué hace OptimizationEngine en su lugar.

Dame casos: query válida, tabla inexistente, columna inexistente, type mismatch, injection "' OR '1'='1".
Explícame qué error lanza cada una. Simula defensa oral ante el profesor.
```


#### E) PowerShell

```powershell
$COMP="$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler"
foreach ($f in "DataType.java","Column.java","Table.java","SymbolTable.java","SemanticAnalyzer.java") {
  Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\$f" $COMP -Force
}
$AN="$FORK\backend\qwerys-backend\src\main\java\com\qwerys\qwerys_backend\analyzer"
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SemanticAnalyzer.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SchemaAwareSemanticAnalyzer.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\schema" "$AN\schema" -Recurse -Force
git add docs/java-compiler/src/main/java/com/qwerys/compiler/DataType.java
git add docs/java-compiler/src/main/java/com/qwerys/compiler/Column.java
git add docs/java-compiler/src/main/java/com/qwerys/compiler/Table.java
git add docs/java-compiler/src/main/java/com/qwerys/compiler/SymbolTable.java
git add docs/java-compiler/src/main/java/com/qwerys/compiler/SemanticAnalyzer.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/SemanticAnalyzer.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/SchemaAwareSemanticAnalyzer.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/schema/
git commit -m "feat(semantico): Semántica académico + producto - Josué Morales"
git push -u origin feature/josue-morales-semantic
```

---

### PR #5 — Joshua García (Testing)

Esperar merge PR #4.

#### A) Académico

`LexerTest.java`, `ParserTest.java`, `SemanticTest.java`

#### B) Producto tests

`SqlLexerTest.java`, `SqlParserTest.java`, `SchemaAwareSemanticAnalyzerTest.java`, `SqlCommentHandlingTest.java`

#### C) Estudiar

| Archivo | Qué dominar |
|---------|-------------|
| Tests académico | JUnit 5 |
| Repo C++ query1.sql ... query_error3.sql | Mapeo casos |

#### D) Prompt tutor IA

```
Soy Joshua, QA del proyecto QWERYS. Debo dominar JUnit 5 y explicar la suite de tests como si yo la escribí.

Ayúdame a:
1. Explicar LexerTest, ParserTest, SemanticTest del módulo docs/java-compiler.
2. Mapear tests a ejemplos del repo C++ compilador-sql-final (query1.sql válido, query_error1 tabla inexistente, etc.).
3. Preparar informe técnico: migración C++→Java, beneficios (portabilidad, GC, JUnit, Spring), desviaciones del plan (18 reglas optimización, 5 patrones injection, sin WebSocket).
4. Simular preguntas del profesor sobre TDD y cobertura.

Dame plantilla de informe en markdown y 10 preguntas tipo examen con respuestas modelo.
```


#### E) PowerShell

```powershell
New-Item -ItemType Directory -Force -Path "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler"
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\LexerTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\ParserTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\SemanticTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
$TST="$FORK\backend\qwerys-backend\src\test\java\com\qwerys\qwerys_backend\analyzer"
New-Item -ItemType Directory -Force -Path $TST | Out-Null
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SqlLexerTest.java" $TST -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SqlParserTest.java" $TST -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SchemaAwareSemanticAnalyzerTest.java" $TST -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SqlCommentHandlingTest.java" $TST -Force
cd "$FORK\docs\java-compiler"; mvn test
git add docs/java-compiler/src/test/java/com/qwerys/compiler/
git add backend/qwerys-backend/src/test/java/com/qwerys/qwerys_backend/analyzer/
git commit -m "test: suite JUnit académico + pipeline - Joshua García"
git push -u origin feature/joshua-garcia-testing
```

---

## 9. PASO 3 — Pull Request

Base: `Azucena17/REFACTORIZACION-_C-_-JAVA` / `main`. Head: tu fork / tu rama.

Plantilla: Integrante, Fase, archivos producto, archivos académicos, cómo probar (`mvn test`, `docker compose`), C++ → Java.

---

## 10. Verificación FINAL

```powershell
git clone https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
copy .env.example .env
docker compose up --build
cd docs/java-compiler && mvn test
```

---

## 11. Aviso al equipo

1. Detener PRs incompletos
2. Usar **esta guía** fusionada (producto + académico)
3. Marjorie: PR #1b — **su trabajo NO fue por nada**
4. Referencia académica: [`GUIA_INTEGRANTES_DEFINITIVA.md`](GUIA_INTEGRANTES_DEFINITIVA.md)
5. Cada quien desde **su cuenta GitHub**

---

*QWERYS · Compiladores UMG 2026 · Guía de entrega completa*
