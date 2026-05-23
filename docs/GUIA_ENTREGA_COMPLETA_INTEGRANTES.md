# Guía de entrega completa por integrante — repo del profesor

**Universidad Mariano Gálvez · Compiladores · Ing. Richard Ortiz · Ciclo 2026 Sección A**  
**Entrega:** sábado 23 de mayo de 2026, 23:59

> **Documento fusionado (guía principal).** Producto + académico + prompts IA.

| Documento | Uso |
|-----------|-----|
| **Este archivo** | Entrega completa — seguir este |
| [`GUIA_INTEGRANTES_DEFINITIVA.md`](GUIA_INTEGRANTES_DEFINITIVA.md) | Referencia académica original |
| [`ENTREGA_REPO_PROFESOR.md`](ENTREGA_REPO_PROFESOR.md) | Detalle extendido |
| [`../GUIA_EQUIPO.md`](../GUIA_EQUIPO.md) | Correr la app |

---

## 0. Meta real de la entrega

### Qué debe lograr el equipo

Al terminar los **5 PR mergeados** en `Azucena17/REFACTORIZACION-_C-_-JAVA`, cualquier persona debe poder:

```powershell
git clone https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
copy .env.example .env
# editar JWT_SECRET (mn. 32 caracteres)
docker compose up --build
```

→ Abrir **http://localhost** y demostrar QWERYS (la misma app que corre desde el repo del equipo).

Adems, en `docs/java-compiler/` debe compilar el **compilador acadmico** de consola (`mvn test`).

### Estructura FINAL del repo del profesor (igual al del equipo)

```
REFACTORIZACION-_C-_-JAVA/
→→→ backend/qwerys-backend/       → Spring Boot
→→→ frontend/qwerys-frontend/     → Angular 17
→→→ docs/java-compiler/           → Compilador acadmico (nombres del plan)
→→→ docker-compose.yml
→→→ .env.example
→→→ README.md
→→→ GUIA_EQUIPO.md
→→→ .gitignore
```

**No** dejar solo `pom.xml` suelto en la raz  eso era una entrega **incompleta**.

### Dos capas  ambas obligatorias

| Capa | Ubicacin en el fork | Quin la completa |
|------|----------------------|-------------------|
| **Producto QWERYS** (exposicin, Docker, web) | `backend/`, `frontend/`, `docker-compose.yml` | Todos  segn rol Gantt |
| **Acadmica** (compilador consola, nombres del curso) | `docs/java-compiler/` | Todos  segn plan del curso |

---



## 1. Qu pasa con el PR #1 que ya hizo Marjorie→

**No fue por nada. No hay que borrarlo.**

Si Marjorie ya subi archivos en la **raz** del fork (`pom.xml`, `src/main/...` en la raz):

| Situacin | Qu hacer |
|-----------|-----------|
| PR **an no mergeado** | **Ampliar** el mismo PR o abrir **PR #1b** `feature/marjorie-giron-arquitectura-v2` con la estructura correcta (mover acadmico a `docs/java-compiler/` + subir monorepo). Cerrar el PR viejo si queda obsoleto. |
| PR **ya mergeado** en `main` | Abrir **PR #1b** que: (1) mueve lo acadmico a `docs/java-compiler/`, (2) agrega `backend/`, `frontend/`, `docker-compose.yml`, etc. |
| Archivos solo en rama local | Reorganizar antes del push definitivo |

**Correcciones obligatorias** (ya aplicadas en el repo del equipo desde mayo 2026):

- `docs/java-compiler/pom.xml` lnea 25: `<!--` (sin `\`)
- `Token.java` y dems `.java` acadmicos: `!` (sin `\!`)

Copiar siempre desde el repo del equipo **actualizado** (`git pull`).

---



## 2. Reglas que el profesor ver en GitHub

1. **Cada integrante** hace fork en **su cuenta**  no todos desde `SamGMorales`.
2. **Cada integrante** configura Git con **su nombre y email**.
3. **Cada integrante** abre **su PR** desde **su rama**  el profesor ve 5 autores distintos.
4. **Nadie** hace push directo a `main`.
5. **Orden de merge:** Marjorie → Juanita → Mercedes → Josu → Joshua.
6. **No** subir `.env`, `application.properties`, ni API keys.
7. **No** inventar Swagger, WebSocket, 13 patrones injection, ni cobertura % sin JaCoCo.

---



## 3. Tres repositorios

| Repositorio | URL | Para qu |
|-------------|-----|----------|
| **Equipo (origen para copiar)** | https://github.com/SamGMorales/qwerys-compiladores-2026 | Cdigo completo ya funcionando |
| **Profesor (entrega calificada)** | https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA | 5 PR  debe quedar igual de funcional |
| **C++ referencia** | https://github.com/compilations-teams/compilador-sql-final | Estudio / demo oral |

---



## 4. Equipo, ramas y orden

| # | Integrante | Carn | Rama Git |
|---|------------|-------|----------|
| 1 | Marjorie Samantha Girn Morales | 1890-22-19957 | `feature/marjorie-giron-arquitectura` |
| 2 | Juanita Raguex Tzum | 1890-20-544 | `feature/juanita-raguex-lexer` |
| 3 | Mercedes Azucena Lpez Prez | 1890-20-11489 | `feature/mercedes-lopez-parser-ast` |
| 4 | Josu David Morales Ramrez | 1890-23-10545 | `feature/josue-morales-semantic` |
| 5 | Joshua Eduardo Garca Reyes | 1890-22-5831 | `feature/joshua-garcia-testing` |

---



## 5. Plan Gantt vs. cdigo real (memorizar para la exposicin)

| Plan Gantt | QWERYS real | Qu decir |
|------------|-------------|-----------|
| `Lexer.java` | `SqlLexer.java` + acadmico `Lexer.java` | Migracin + extensin multi-dialecto |
| `Parser.java` | `SqlParser.java` + `AstNode.java` | Sin `ASTBuilder` separado |
| `SymbolTable.java` | `SemanticAnalyzer` + schema live | Evolucin de tabla de smbolos |
| Codegen / IR | **No**  `OptimizationEngine` (18 reglas) | Sugerencias, no bytecode |
| 13 injection | **5** patrones (`SE007`) | Integrado en semntica |
| WebSocket | **REST** `POST /api/queries/analyze` | |
| Swagger | **No**  README + controllers | |

---



## 6. PASO 0  Entorno (todos)

### Herramientas

Git  Java 17  Node.js 20  Docker Desktop  (opcional) Maven global

### PowerShell  no uses cmd con `$HOME`

```powershell
git --version
java -version
node --version
docker --version
```

### Identidad Git (obligatorio  afecta la nota)

```powershell
git config --global user.name "Tu Nombre Completo"
git config --global user.email "tu-email-de-github@ejemplo.com"
```

---



## 7. PASO 1  Fork, clone y variables

### 7.1 Fork del repo del profesor

1. https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA  
2. **Fork** → tu cuenta  
3. **NO marcar** Copy only one branch

### 7.2 Clonar TU fork

```powershell
cd $HOME\Documents
git clone https://github.com/TU-USUARIO/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
git checkout main
git pull origin main
```

### 7.3 Origen del cdigo (repo del equipo)

Si ya tienes el proyecto local:

```powershell
$REF = "C:\Users\TU_USUARIO\OneDrive\Documentos\qwerys-project"
# o si clonaste el repo del equipo:
# $REF = "$HOME\Documents\qwerys-compiladores-2026"

$FORK = "$HOME\Documents\REFACTORIZACION-_C-_-JAVA"
$BE   = "$REF\backend\qwerys-backend"
$FE   = "$REF\frontend\qwerys-frontend"
$ACAD = "$REF\docs\java-compiler"
```

Actualizar origen antes de copiar:

```powershell
cd $REF
git pull origin main
```

### 7.4 Crear tu rama

```powershell
cd $FORK
git checkout main
git pull origin main
git checkout -b feature/TU-RAMA-EXACTA
```

---



## 8. PASO 2 — Qué sube CADA integrante (producto + académico)

Copia **solo tus archivos**. Revisa `git status`. No `git add .` a ciegas.

### PR #1 — Marjorie Girón (Arquitecto — Fase 1)

#### A) PRODUCTO QWERYS

### PR #1  Marjorie Girn (Arquitecto)

**Rol Gantt:** Estructura del proyecto, tokens base, Spring Boot, Docker, frontend, optimizacin (18 reglas).

#### A) Raz del monorepo

| Copiar desde `$REF` | A `$FORK` |
|---------------------|-----------|
| `docker-compose.yml` | idem |
| `.env.example` | idem |
| `README.md` | idem |
| `GUIA_EQUIPO.md` | idem |
| `.gitignore` (raz del monorepo) | idem |

```powershell
Copy-Item "$REF\docker-compose.yml" "$FORK\" -Force
Copy-Item "$REF\.env.example" "$FORK\" -Force
Copy-Item "$REF\README.md" "$FORK\" -Force
Copy-Item "$REF\GUIA_EQUIPO.md" "$FORK\" -Force
Copy-Item "$REF\.gitignore" "$FORK\" -Force
```

#### B) Frontend completo (Angular 17 + Monaco)

```powershell
Copy-Item "$FE" "$FORK\frontend\qwerys-frontend" -Recurse -Force
```

#### C) Backend  infraestructura y mdulos de arquitecto

Copia el backend **excepto** los archivos que suben Juanita, Mercedes, Josu y Joshua (seccin de ellos).

```powershell
# Backend completo primero
Copy-Item "$BE" "$FORK\backend\qwerys-backend" -Recurse -Force

# Quitar archivos de otros integrantes (los subirn en su PR)
$AN = "$FORK\backend\qwerys-backend\src\main\java\com\qwerys\qwerys_backend\analyzer"
Remove-Item "$AN\SqlLexer.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\Token.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\TokenType.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\StatementSplitter.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\SqlParser.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\AstNode.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\SemanticError.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\SqlDialect.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\SemanticAnalyzer.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\SchemaAwareSemanticAnalyzer.java" -ErrorAction SilentlyContinue
Remove-Item "$AN\schema" -Recurse -ErrorAction SilentlyContinue
Remove-Item "$FORK\backend\qwerys-backend\src\test" -Recurse -ErrorAction SilentlyContinue
```

Marjorie **s incluye** en su PR: `optimization/`, `adapter/`, `ai/`, `config/`, `controller/`, `service/`, `dto/`, `model/`, lexers/analyzers NoSQL, `procedural/`, dialect analyzers, `QwerysBackendApplication.java`, `pom.xml`, `mvnw*`, `Dockerfile`, `src/main/resources/application*.properties.example`, etc.

#### D) Mdulo acadmico  Fase 1 (en la ruta correcta)

```powershell
New-Item -ItemType Directory -Force -Path "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler"

Copy-Item "$ACAD\pom.xml" "$FORK\docs\java-compiler\" -Force
Copy-Item "$ACAD\README.md" "$FORK\docs\java-compiler\" -Force
Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\TokenType.java" "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\Token.java" "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\Main.java" "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler\" -Force
```

#### E) Verificar (Marjorie)

```powershell
cd "$FORK\docs\java-compiler"
mvn compile
# Puede fallar por Lexer/Parser faltantes en Main.java  NORMAL en Fase 1 acadmica

cd $FORK
# Backend an no compila hasta PR #2#4  NORMAL
```

#### F) Commit y push

```powershell
cd $FORK
git status
git add docker-compose.yml .env.example README.md GUIA_EQUIPO.md .gitignore
git add frontend/
git add backend/
git add docs/java-compiler/

git commit -m "feat(arquitectura): monorepo QWERYS - Docker, backend, frontend, optimizacin, acadmico Fase 1

Responsable: Marjorie Samantha Girn Morales - 1890-22-19957
Incluye: docker-compose, Spring Boot shell, Angular, optimization/, docs/java-compiler tokens+Main
Nota: compilacin completa backend tras PR #2-4; acadmico tras Lexer/Parser/Semntica"

git push -u origin feature/marjorie-giron-arquitectura
```

---

#### B) ACADÉMICO + estudio + prompt IA

### Integrante 1  Marjorie Girón (Arquitecto)  PR #1

**Rol:** Estructura del proyecto, tokens base, orquestación Spring Boot, Docker, frontend, optimización.

#### A) Subir al repo del profesor (módulo académico)

| Copiar desde `$REF\docs\java-compiler\` | A `$FORK\` |
|------------------------------------------|------------|
| `pom.xml` | `pom.xml` |
| `README.md` | `README.md` |
| `src/main/java/com/qwerys/compiler/TokenType.java` | misma ruta |
| `src/main/java/com/qwerys/compiler/Token.java` | misma ruta |
| `src/main/java/com/qwerys/compiler/Main.java` | misma ruta |

```powershell
$REF = "$HOME\Documents\qwerys-compiladores-2026"
$FORK = "$HOME\Documents\REFACTORIZACION-_C-_-JAVA"
Copy-Item "$REF\docs\java-compiler\pom.xml" "$FORK\" -Force
Copy-Item "$REF\docs\java-compiler\README.md" "$FORK\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\Token*.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\Main.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
cd $FORK
.\mvnw.cmd compile
git add .
git commit -m "feat(arquitectura): estructura base compilador Java - pom, tokens, Main (Fase 1)"
git push -u origin feature/marjorie-giron-arquitectura
```

#### B) Estudiar en QWERYS completo

| Área | Archivos |
|------|----------|
| Orquestación | `backend/.../service/QueryAnalysisService.java` |
| API | `backend/.../controller/QueryController.java` |
| Docker | `docker-compose.yml`, `application-docker.properties` |
| Optimización (18 reglas) | `backend/.../optimization/OptimizationEngine.java` |
| Frontend | `frontend/qwerys-frontend/` |

#### Prompt tutor IA  Marjorie

```
Soy Marjorie, arquitecta de QWERYS (migración compilador SQL C++ → Java, Spring Boot 3.5 + Angular 17).
Debo dominar en 2 días:
1) QueryAnalysisService: lexer → parser → semántica → OptimizationEngine → JSON.
2) docker-compose: MySQL, PostgreSQL, backend:8080, frontend nginx:80.
3) 18 reglas de optimización (no 10). Sin WebSocket  usamos REST POST /api/queries/analyze. Sin Swagger.

Explícame el flujo de "SELECT id FROM users WHERE id=1" con diagrama ASCII.
Hazme 10 preguntas de examen oral y corrige mis respuestas citando clases reales del repo.
```

---



---

### PR #2 — Juanita Raguex (Léxico — Fase 2)

#### A) PRODUCTO QWERYS

### PR #2  Juanita Raguex (Lxico)

**Esperar** merge del PR #1 de Marjorie.

```powershell
cd $FORK
git checkout main
git pull origin main
git checkout -b feature/juanita-raguex-lexer
```

#### Acadmico

```powershell
New-Item -ItemType Directory -Force -Path "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler"
Copy-Item "$ACAD\src\main\java\com\qwerys\compiler\Lexer.java" `
  "$FORK\docs\java-compiler\src\main\java\com\qwerys\compiler\" -Force
```

#### Producto QWERYS

```powershell
$AN = "$FORK\backend\qwerys-backend\src\main\java\com\qwerys\qwerys_backend\analyzer"
New-Item -ItemType Directory -Force -Path $AN
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SqlLexer.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\Token.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\TokenType.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\StatementSplitter.java" $AN -Force
```

#### Verificar

```powershell
cd "$FORK\docs\java-compiler"
mvn compile
```

#### Commit

```powershell
cd $FORK
git add docs/java-compiler/src/main/java/com/qwerys/compiler/Lexer.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/SqlLexer.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/Token.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/TokenType.java
git add backend/qwerys-backend/src/main/java/com/qwerys/qwerys_backend/analyzer/StatementSplitter.java
git commit -m "feat(lexer): Lexer.java academico + SqlLexer producto (Fase 2)

Responsable: Juanita Raguex Tzum - 1890-20-544"
git push -u origin feature/juanita-raguex-lexer
```

---

#### B) ACADÉMICO + estudio + prompt IA

### Integrante 2  Juanita Raguex (Analizador léxico)  PR #2

**Rol:** Convertir texto SQL en tokens (tipo, valor, línea, columna).

#### A) Subir al repo del profesor

| Archivo | Origen |
|---------|--------|
| `src/main/java/com/qwerys/compiler/Lexer.java` | `$REF\docs\java-compiler\...\Lexer.java` |

```powershell
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\Lexer.java" `
  "$FORK\src\main\java\com\qwerys\compiler\" -Force
git add .
git commit -m "feat(lexer): migrar analizador lexico C++ a Java - Lexer.java (Fase 2)

Responsable: Juanita Raguex Tzum - 1890-20-544"
git push -u origin feature/juanita-raguex-lexer
```

#### B) Estudiar en QWERYS completo (rutas correctas)

| Archivo | Nota |
|---------|------|
| `backend/.../analyzer/SqlLexer.java` | Lexer SQL principal |
| `backend/.../analyzer/Token.java` | Token |
| `backend/.../analyzer/TokenType.java` | Tipos |
| `backend/.../analyzer/StatementSplitter.java` | Multi-sentencia |
| `backend/.../analyzer/SqlLexerTest.java` | **1 test** (no "15+ casos") |

**C++ referencia:** `Lexer.cpp`, `Token.h` en `compilador-sql-final`.

> **No existe** `ErrorManager.java`  los errores léxicos van en el flujo del parser/semántica.

#### Prompt tutor IA  Juanita

```
Soy Juanita, responsable del analizador léxico en QWERYS (Compiladores UMG).
Migré Lexer.cpp → Lexer.java (académico) y el producto usa SqlLexer.java.

Enséñame:
1) Fase léxica con "SELECT nombre FROM usuarios"  tabla token|tipo|línea|columna.
2) Diferencia Lexer.cpp (3 keywords) vs SqlLexer.java (multi-dialecto).
3) Qué pasa con comentarios -- y strings 'O''Brien'.
4) 10 preguntas orales del profesor sobre autómatas finitos y tokens.

NO digas WebSocket ni ErrorManager  no existen en nuestro código.
```

---



---

### PR #3 — Mercedes López (Parser + AST — Fase 3)

#### A) PRODUCTO QWERYS

### PR #3  Mercedes Lpez (Parser + AST)

```powershell
cd $FORK
git checkout main && git pull origin main
git checkout -b feature/mercedes-lopez-parser-ast
```

#### Acadmico  copiar todos:

`CompOperator.java`, `ASTNode.java`, `ExpressionNode.java`, `ConditionNode.java`, `SelectNode.java`, `Parser.java`

#### Producto

```powershell
$AN = "$FORK\backend\qwerys-backend\src\main\java\com\qwerys\qwerys_backend\analyzer"
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SqlParser.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\AstNode.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SemanticError.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SqlDialect.java" $AN -Force
```

```powershell
cd "$FORK\docs\java-compiler"
mvn compile
git add (tus archivos)
git commit -m "feat(parser): Parser + AST acadmico y SqlParser producto (Fase 3)

Responsable: Mercedes Azucena Lpez Prez - 1890-20-11489"
git push -u origin feature/mercedes-lopez-parser-ast
```

---

#### B) ACADÉMICO + estudio + prompt IA

### Integrante 3  Mercedes López (Parser + AST)  PR #3

**Rol:** Tokens → árbol sintáctico (AST).

#### A) Subir al repo del profesor

| Archivo | Origen en `$REF\docs\java-compiler\...` |
|---------|----------------------------------------|
| `CompOperator.java` | idem |
| `ASTNode.java` | idem |
| `ExpressionNode.java` | idem |
| `ConditionNode.java` | idem |
| `SelectNode.java` | idem |
| `Parser.java` | idem |

```powershell
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\CompOperator.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\ASTNode.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\ExpressionNode.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\ConditionNode.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\SelectNode.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\Parser.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
git add .
git commit -m "feat(parser): migrar parser recursivo y AST - Parser.java, nodos AST (Fase 3)

Responsable: Mercedes Azucena Lopez Perez - 1890-20-11489"
git push -u origin feature/mercedes-lopez-parser-ast
```

#### B) Estudiar en QWERYS completo

| Archivo | Nota |
|---------|------|
| `SqlParser.java` | ~3500 LOC, parser recursivo |
| `AstNode.java` | AST genérico |
| `SqlParserTest.java` | **7 tests** (no "20+") |
| `SemanticError.java`, `SqlDialect.java` | Apoyo al pipeline |

> **No existe** `ASTBuilder.java`  el parser construye el AST directamente.

#### Prompt tutor IA  Mercedes

```
Soy Mercedes, responsable del parser y AST en QWERYS.
Parser.cpp → Parser.java (académico). Producto: SqlParser.java + AstNode.java. Sin ASTBuilder.

Enséñame gramática SELECT del C++, parser recursivo descendente, ParseException con línea/columna.
Dibuja AST de: SELECT id, name FROM users WHERE id = 1.
Compara parser C++ (solo SELECT) vs SqlParser.java (JOIN, CTE, subqueries).
10 preguntas orales con corrección.
```

---



---

### PR #4 — Josué Morales (Semántica — Fase 4)

#### A) PRODUCTO QWERYS

### PR #4  Josu Morales (Semntica + smbolos + injection)

#### Acadmico

`DataType.java`, `Column.java`, `Table.java`, `SymbolTable.java`, `SemanticAnalyzer.java`

#### Producto

```powershell
$AN = "$FORK\backend\qwerys-backend\src\main\java\com\qwerys\qwerys_backend\analyzer"
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SemanticAnalyzer.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\SchemaAwareSemanticAnalyzer.java" $AN -Force
Copy-Item "$BE\src\main\java\com\qwerys\qwerys_backend\analyzer\schema" "$AN\schema" -Recurse -Force
```

```powershell
cd "$FORK\docs\java-compiler"
mvn test
git commit -m "feat(semantico): SymbolTable, SemanticAnalyzer, schema live, 5 patrones SE007 (Fase 4)

Responsable: Josu David Morales Ramrez - 1890-23-10545"
git push -u origin feature/josue-morales-semantic
```

---

#### B) ACADÉMICO + estudio + prompt IA

### Integrante 4  Josué Morales (Semántico + símbolos + injection)  PR #4

**Rol:** Validar tablas, columnas, tipos; detectar SQL injection.

#### A) Subir al repo del profesor

| Archivo | Origen |
|---------|--------|
| `DataType.java` | `$REF\docs\java-compiler\...` |
| `Column.java` | idem |
| `Table.java` | idem |
| `SymbolTable.java` | idem |
| `SemanticAnalyzer.java` | idem |

```powershell
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\DataType.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\Column.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\Table.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\SymbolTable.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\main\java\com\qwerys\compiler\SemanticAnalyzer.java" "$FORK\src\main\java\com\qwerys\compiler\" -Force
git add .
git commit -m "feat(semantico): SymbolTable y SemanticAnalyzer - validacion e injection SE007 (Fase 4)

- 5 patrones heurísticos SQL injection (no 13)
- Tabla de simbolos con usuarios/productos

Responsable: Josue David Morales Ramirez - 1890-23-10545"
git push -u origin feature/josue-morales-semantic
```

#### B) Estudiar en QWERYS completo

| Archivo | Nota |
|---------|------|
| `SemanticAnalyzer.java` | Buscar `INJECTION_STRING_PATTERNS`, `INJECTION_STRUCTURAL_PATTERNS`, `SE007` |
| `SchemaAwareSemanticAnalyzer.java` | Validación con schema |
| `SchemaAwareSemanticAnalyzerTest.java` | Tests (ruta: `analyzer/`, no raíz) |
| `ProceduralSemanticAnalyzerTest.java` | Tests procedural |

**No copies toda la carpeta `model/`**  incluye DTOs de auth/IA que no son tu módulo académico.

> **No hay Swagger.** **No hay 13 patrones**  son **5**.  
> **No hay** `IntermediateCodeGenerator`  Marjorie/arquitectura usa `OptimizationEngine`.

#### Prompt tutor IA  Josué

```
Soy Josué, responsable del analizador semántico y SymbolTable en QWERYS.

Debo dominar:
1) SymbolTable.java académico (usuarios, productos, tipos INT/VARCHAR/FLOAT).
2) SemanticAnalyzer producto: 5 patrones injection → código SE007 (NO 13, NO Swagger).
3) Por qué no hay IntermediateCodeGenerator  OptimizationEngine da sugerencias AST.
4) Casos: query válida, tabla clientes inexistente, columna email inexistente, edad > 'hola', "' OR '1'='1".

Simula defensa oral ante el profesor. Corrige si digo "13 patrones" o "Swagger".
```

---



---

### PR #5 — Joshua García (QA — Fase 5)

#### A) PRODUCTO QWERYS

### PR #5  Joshua Garca (QA / Testing)

#### Acadmico

```powershell
New-Item -ItemType Directory -Force -Path "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler"
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\LexerTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\ParserTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\SemanticTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
```

#### Producto  tests principales del pipeline compilador

```powershell
$TST = "$FORK\backend\qwerys-backend\src\test\java\com\qwerys\qwerys_backend"
New-Item -ItemType Directory -Force -Path "$TST\analyzer"
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SqlLexerTest.java" "$TST\analyzer\" -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SqlParserTest.java" "$TST\analyzer\" -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SchemaAwareSemanticAnalyzerTest.java" "$TST\analyzer\" -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\ProceduralSemanticAnalyzerTest.java" "$TST\analyzer\" -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\optimization" "$TST\optimization" -Recurse -Force
```

Opcional: aadir en el PR un `docs/INFORME_MIGRACION.md` (12 pginas) con captura de `mvn test` BUILD SUCCESS.

```powershell
cd "$FORK\docs\java-compiler"
mvn test
cd "$FORK\backend\qwerys-backend"
.\mvnw.cmd test
git commit -m "test: suite JUnit acadmica + tests pipeline QWERYS (Fase 5)

Responsable: Joshua Eduardo Garca Reyes - 1890-22-5831"
git push -u origin feature/joshua-garcia-testing
```

---

#### B) ACADÉMICO + estudio + prompt IA

### Integrante 5  Joshua García (QA / Testing)  PR #5 (último)

**Rol:** Tests JUnit 5, documentación técnica, evidencia de calidad.

#### A) Subir al repo del profesor

| Archivo | Origen |
|---------|--------|
| `LexerTest.java` | `$REF\docs\java-compiler\src\test\...\LexerTest.java` |
| `ParserTest.java` | idem |
| `SemanticTest.java` | idem |

```powershell
Copy-Item "$REF\docs\java-compiler\src\test\java\com\qwerys\compiler\LexerTest.java" "$FORK\src\test\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\test\java\com\qwerys\compiler\ParserTest.java" "$FORK\src\test\java\com\qwerys\compiler\" -Force
Copy-Item "$REF\docs\java-compiler\src\test\java\com\qwerys\compiler\SemanticTest.java" "$FORK\src\test\java\com\qwerys\compiler\" -Force
cd $FORK
mvn test
git add .
git commit -m "test: suite JUnit5 LexerTest, ParserTest, SemanticTest (Fase 2-4)

Responsable: Joshua Eduardo Garcia Reyes - 1890-22-5831"
git push -u origin feature/joshua-garcia-testing
```

#### B) Referencia QWERYS completo (estudio, no subir todo de golpe)

| Test | Tests reales |
|------|--------------|
| `analyzer/SqlLexerTest.java` | 1 `@Test` |
| `analyzer/SqlParserTest.java` | 7 `@Test` |
| `analyzer/SchemaAwareSemanticAnalyzerTest.java` | Semántica |
| `optimization/OptimizationEngine*Test.java` | 18 reglas |
| ~63 archivos total | No existe `SemanticAnalyzerTest.java` ni `OptimizationEngineTest.java` |

**No afirmes "cobertura 70%"** sin reporte JaCoCo  di: "suite amplia JUnit 5, `mvn test` pasa".

#### Documentación que puedes añadir en tu PR

- README con endpoints REST (no Swagger)
- Informe C++ vs Java (12 páginas)
- Captura de `mvn test` → BUILD SUCCESS

#### Prompt tutor IA  Joshua

```
Soy Joshua, QA de QWERYS. Debo dominar JUnit 5 y la documentación de entrega.

Ayúdame con:
1) LexerTest, ParserTest, SemanticTest en docs/java-compiler.
2) Mapeo a query1.sql  query_error3.sql del repo C++ compilador-sql-final.
3) Informe técnico: migración C++, beneficios Java, desviaciones (18 reglas, 5 injection, sin WebSocket/Swagger/codegen).
4) 10 preguntas del profesor sobre TDD con respuestas modelo.

No inventes SemanticAnalyzerTest.java ni cobertura 75% medida  no tenemos JaCoCo configurado.
```

---



---

## 9. PASO 3  Pull Request (todos)

1. Fork propio → rama propia → push  
2. GitHub → **Compare & pull request**  
3. **Base:** `Azucena17/REFACTORIZACION-_C-_-JAVA` → `main`  
4. **Head:** tu fork → tu rama  

### Plantilla PR

```markdown
## Integrante
[Nombre]  [Carn]

## Fase / Rol Gantt
(ej. Fase 2  Analizador lxico)

## Archivos producto QWERYS
- (lista rutas backend/frontend)

## Archivos acadmicos
- docs/java-compiler/...

## Cmo probar
cd docs/java-compiler && mvn test
cd backend/qwerys-backend && ./mvnw test
docker compose up --build  (solo cuando main tenga todo mergeado)

## Correspondencia C++ → Java
Lexer.cpp → Lexer.java / SqlLexer.java

## Revisores
@SamGMorales @Azucena17
```

**Token GitHub:** solo si `git push` pide autenticacin (Settings → Developer settings → PAT classic → scope `repo`).

---

## 10. Verificacin FINAL (cuando los 5 PR estn mergeados en main)

```powershell
git clone https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
copy .env.example .env
# JWT_SECRET mnimo 32 caracteres
docker compose up --build
```

→ http://localhost  **misma demo que el repo del equipo**.

```powershell
cd docs/java-compiler
mvn test
```

→ BUILD SUCCESS  compilador acadmico.

En GitHub → **Insights → Contributors** deben aparecer **5 integrantes**.

---

## 11. Exposicin  qu decir si preguntan dnde est el trabajo

> Desarrollamos QWERYS en el repo del equipo para integracin continua. La entrega calificada est en el repo del curso: cada integrante abri su PR con su mdulo segn el Gantt  arquitectura, lxico, parser, semntica y QA. Al clonar el repo del profesor y levantar Docker, obtienen el mismo sistema que presentamos.

**No digas:** Swagger, WebSocket, 13 patrones, codegen bytecode, cobertura 75% sin JaCoCo.

---

## 12. Arquitectura (demo oral)

```
Angular + Monaco → POST /api/queries/analyze
    → QueryAnalysisService
    → SqlLexer          (Juanita)
    → SqlParser         (Mercedes)
    → SemanticAnalyzer  (Josu)
    → OptimizationEngine (Marjorie  18 reglas)
    → JSON → frontend
```

---

## 13. Plan estudio 2 das (todos)

| Da | Maana | Tarde |
|-----|--------|-------|
| 1 | Tus archivos en `docs/java-compiler/` | Equivalentes en `backend/.../analyzer/` + repo C++ |
| 2 | Prompt IA tutor (abajo) | Simular oral + Docker |

### Prompts IA por integrante

Ver secciones anteriores en [`ENTREGA_REPO_PROFESOR.md`](ENTREGA_REPO_PROFESOR.md)  prompts de Marjorie, Juanita, Mercedes, Josu y Joshua.

---

## 14. Checklist antes de cada PR

- [ ] Rama con nombre exacto (seccin 4)
- [ ] `git config user.name` es el tuyo
- [ ] Solo **tus** archivos en el commit
- [ ] Copiaste desde `$REF` con `git pull` reciente
- [ ] Acadmico en `docs/java-compiler/` (no suelto en raz)
- [ ] PR con descripcin completa
- [ ] Otro integrante revisa antes del merge
- [ ] Sin `.env` / `application.properties` / API keys

---

## 15. Errores frecuentes (corregidos)

| Error | Verdad / solucin |
|-------|-------------------|
| Solo subir `docs/java-compiler` suelto en raz | Estructura monorepo completa (seccin 0) |
| Todos push desde una cuenta | Cada quien desde **su** GitHub |
| `.\mvnw.cmd` en `docs/java-compiler` | Usar `mvn compile` ah |
| `\!` y `<\!--` en acadmico | Corregido en repo del equipo  `git pull` |
| 13 injection / Swagger / WebSocket | **No existen**  ver seccin 5 |
| Fork copy one branch only | **Desmarcar**  necesitas `main` |

---

## 16. Aviso al equipo  accin inmediata

Si alguien ya sigui la **gua antigua** (solo acadmico en raz):

1. **Detener** PRs incompletos.  
2. Leer **esta gua** desde seccin 0.  
3. Marjorie: PR #1b con monorepo (seccin 8 PR #1).  
4. Dems: esperar merge Marjorie → sus PR con **acadmico + producto**.  
5. Cada quien desde **su cuenta GitHub**.

---

*QWERYS  Compiladores UMG 2026  Guía definitiva  entrega completa al repo del profesor.*


---

*QWERYS · Compiladores UMG 2026 · Guía de entrega completa*
