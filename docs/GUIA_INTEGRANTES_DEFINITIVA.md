# Gu�a definitiva por integrante � entrega COMPLETA al repo del profesor

**Universidad Mariano G�lvez � Compiladores � Ing. Richard Ortiz � Ciclo 2026 Secci�n A**  
**Entrega:** s�bado 23 de mayo de 2026, 23:59

> **Este documento es la fuente principal.** Reemplaza la versi�n anterior que trataba el monorepo QWERYS como �opcional�.  
> **Meta:** que el repo del profesor (`REFACTORIZACION-_C-_-JAVA`) quede **igual de clonable y funcional** que [qwerys-compiladores-2026](https://github.com/SamGMorales/qwerys-compiladores-2026), con **5 integrantes** registrados en GitHub cada uno con **su PR**.

| Documento | Uso |
|-----------|-----|
| **Este archivo** | Qu� sube cada quien + c�mo + verificaci�n final |
| [`../GUIA_EQUIPO.md`](../GUIA_EQUIPO.md) | Correr la app desde el repo del equipo (referencia) |
| [`ENTREGA_REPO_PROFESOR.md`](ENTREGA_REPO_PROFESOR.md) | Detalle acad�mico extendido |
| [`GUIA_INTEGRANTES.md`](GUIA_INTEGRANTES.md) | Borrador antiguo � **no usar solo** |

---

## 0. LEE ESTO PRIMERO � meta real de la entrega

### Qu� debe lograr el equipo

Al terminar los **5 PR mergeados** en `Azucena17/REFACTORIZACION-_C-_-JAVA`, cualquier persona debe poder:

```powershell
git clone https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
copy .env.example .env
# editar JWT_SECRET (m�n. 32 caracteres)
docker compose up --build
```

? Abrir **http://localhost** y demostrar QWERYS (la misma app que corre desde el repo del equipo).

Adem�s, en `docs/java-compiler/` debe compilar el **compilador acad�mico** de consola (`mvn test`).

### Estructura FINAL del repo del profesor (igual al del equipo)

```
REFACTORIZACION-_C-_-JAVA/
??? backend/qwerys-backend/       ? Spring Boot
??? frontend/qwerys-frontend/     ? Angular 17
??? docs/java-compiler/           ? Compilador acad�mico (nombres del plan)
??? docker-compose.yml
??? .env.example
??? README.md
??? GUIA_EQUIPO.md
??? .gitignore
```

**No** dejar solo `pom.xml` suelto en la ra�z � eso era una entrega **incompleta**.

### Dos capas � ambas obligatorias

| Capa | Ubicaci�n en el fork | Qui�n la completa |
|------|----------------------|-------------------|
| **Producto QWERYS** (exposici�n, Docker, web) | `backend/`, `frontend/`, `docker-compose.yml` | Todos � seg�n rol Gantt |
| **Acad�mica** (compilador consola, nombres del curso) | `docs/java-compiler/` | Todos � seg�n plan del curso |

---

## 1. �Qu� pasa con el PR #1 que ya hizo Marjorie?

**No fue por nada. No hay que borrarlo.**

Si Marjorie ya subi� archivos en la **ra�z** del fork (`pom.xml`, `src/main/...` en la ra�z):

| Situaci�n | Qu� hacer |
|-----------|-----------|
| PR **a�n no mergeado** | **Ampliar** el mismo PR o abrir **PR #1b** `feature/marjorie-giron-arquitectura-v2` con la estructura correcta (mover acad�mico a `docs/java-compiler/` + subir monorepo). Cerrar el PR viejo si queda obsoleto. |
| PR **ya mergeado** en `main` | Abrir **PR #1b** que: (1) mueve lo acad�mico a `docs/java-compiler/`, (2) agrega `backend/`, `frontend/`, `docker-compose.yml`, etc. |
| Archivos solo en rama local | Reorganizar antes del push definitivo |

**Correcciones obligatorias** (ya aplicadas en el repo del equipo desde mayo 2026):

- `docs/java-compiler/pom.xml` l�nea 25: `<!--` (sin `\`)
- `Token.java` y dem�s `.java` acad�micos: `!` (sin `\!`)

Copiar siempre desde el repo del equipo **actualizado** (`git pull`).

---

## 2. Reglas que el profesor ver� en GitHub

1. **Cada integrante** hace fork en **su cuenta** � no todos desde `SamGMorales`.
2. **Cada integrante** configura Git con **su nombre y email**.
3. **Cada integrante** abre **su PR** desde **su rama** � el profesor ve 5 autores distintos.
4. **Nadie** hace push directo a `main`.
5. **Orden de merge:** Marjorie ? Juanita ? Mercedes ? Josu� ? Joshua.
6. **No** subir `.env`, `application.properties`, ni API keys.
7. **No** inventar Swagger, WebSocket, 13 patrones injection, ni cobertura % sin JaCoCo.

---

## 3. Tres repositorios

| Repositorio | URL | Para qu� |
|-------------|-----|----------|
| **Equipo (origen para copiar)** | https://github.com/SamGMorales/qwerys-compiladores-2026 | C�digo completo ya funcionando |
| **Profesor (entrega calificada)** | https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA | 5 PR � debe quedar igual de funcional |
| **C++ referencia** | https://github.com/compilations-teams/compilador-sql-final | Estudio / demo oral |

---

## 4. Equipo, ramas y orden

| # | Integrante | Carn� | Rama Git |
|---|------------|-------|----------|
| 1 | Marjorie Samantha Gir�n Morales | 1890-22-19957 | `feature/marjorie-giron-arquitectura` |
| 2 | Juanita Raguex Tzum | 1890-20-544 | `feature/juanita-raguex-lexer` |
| 3 | Mercedes Azucena L�pez P�rez | 1890-20-11489 | `feature/mercedes-lopez-parser-ast` |
| 4 | Josu� David Morales Ram�rez | 1890-23-10545 | `feature/josue-morales-semantic` |
| 5 | Joshua Eduardo Garc�a Reyes | 1890-22-5831 | `feature/joshua-garcia-testing` |

---

## 5. Plan Gantt vs. c�digo real (memorizar para la exposici�n)

| Plan Gantt | QWERYS real | Qu� decir |
|------------|-------------|-----------|
| `Lexer.java` | `SqlLexer.java` + acad�mico `Lexer.java` | Migraci�n + extensi�n multi-dialecto |
| `Parser.java` | `SqlParser.java` + `AstNode.java` | Sin `ASTBuilder` separado |
| `SymbolTable.java` | `SemanticAnalyzer` + schema live | Evoluci�n de tabla de s�mbolos |
| Codegen / IR | **No** � `OptimizationEngine` (18 reglas) | Sugerencias, no bytecode |
| 13 injection | **5** patrones (`SE007`) | Integrado en sem�ntica |
| WebSocket | **REST** `POST /api/queries/analyze` | |
| Swagger | **No** � README + controllers | |

---

## 6. PASO 0 � Entorno (todos)

### Herramientas

Git � Java 17 � Node.js 20 � Docker Desktop � (opcional) Maven global

### PowerShell � no uses cmd con `$HOME`

```powershell
git --version
java -version
node --version
docker --version
```

### Identidad Git (obligatorio � afecta la nota)

```powershell
git config --global user.name "Tu Nombre Completo"
git config --global user.email "tu-email-de-github@ejemplo.com"
```

---

## 7. PASO 1 � Fork, clone y variables

### 7.1 Fork del repo del profesor

1. https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA  
2. **Fork** ? tu cuenta  
3. **NO marcar** �Copy only one branch�

### 7.2 Clonar TU fork

```powershell
cd $HOME\Documents
git clone https://github.com/TU-USUARIO/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
git checkout main
git pull origin main
```

### 7.3 Origen del c�digo (repo del equipo)

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

## 8. PASO 2 � Qu� sube CADA integrante

**Regla:** copia **solo tus archivos/carpetas** listados abajo.  
**No uses** `git add .` sin revisar `git status` antes.

---

### PR #1 � Marjorie Gir�n (Arquitecto)

**Rol Gantt:** Estructura del proyecto, tokens base, Spring Boot, Docker, frontend, optimizaci�n (18 reglas).

#### A) Ra�z del monorepo

| Copiar desde `$REF` | A `$FORK` |
|---------------------|-----------|
| `docker-compose.yml` | idem |
| `.env.example` | idem |
| `README.md` | idem |
| `GUIA_EQUIPO.md` | idem |
| `.gitignore` (ra�z del monorepo) | idem |

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

#### C) Backend � infraestructura y m�dulos de arquitecto

Copia el backend **excepto** los archivos que suben Juanita, Mercedes, Josu� y Joshua (secci�n de ellos).

```powershell
# Backend completo primero
Copy-Item "$BE" "$FORK\backend\qwerys-backend" -Recurse -Force

# Quitar archivos de otros integrantes (los subir�n en su PR)
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

Marjorie **s� incluye** en su PR: `optimization/`, `adapter/`, `ai/`, `config/`, `controller/`, `service/`, `dto/`, `model/`, lexers/analyzers NoSQL, `procedural/`, dialect analyzers, `QwerysBackendApplication.java`, `pom.xml`, `mvnw*`, `Dockerfile`, `src/main/resources/application*.properties.example`, etc.

#### D) M�dulo acad�mico � Fase 1 (en la ruta correcta)

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
# Puede fallar por Lexer/Parser faltantes en Main.java � NORMAL en Fase 1 acad�mica

cd $FORK
# Backend a�n no compila hasta PR #2�#4 � NORMAL
```

#### F) Commit y push

```powershell
cd $FORK
git status
git add docker-compose.yml .env.example README.md GUIA_EQUIPO.md .gitignore
git add frontend/
git add backend/
git add docs/java-compiler/

git commit -m "feat(arquitectura): monorepo QWERYS - Docker, backend, frontend, optimizaci�n, acad�mico Fase 1

Responsable: Marjorie Samantha Gir�n Morales - 1890-22-19957
Incluye: docker-compose, Spring Boot shell, Angular, optimization/, docs/java-compiler tokens+Main
Nota: compilaci�n completa backend tras PR #2-4; acad�mico tras Lexer/Parser/Sem�ntica"

git push -u origin feature/marjorie-giron-arquitectura
```

---

### PR #2 � Juanita Raguex (L�xico)

**Esperar** merge del PR #1 de Marjorie.

```powershell
cd $FORK
git checkout main
git pull origin main
git checkout -b feature/juanita-raguex-lexer
```

#### Acad�mico

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

### PR #3 � Mercedes L�pez (Parser + AST)

```powershell
cd $FORK
git checkout main && git pull origin main
git checkout -b feature/mercedes-lopez-parser-ast
```

#### Acad�mico � copiar todos:

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
git commit -m "feat(parser): Parser + AST acad�mico y SqlParser producto (Fase 3)

Responsable: Mercedes Azucena L�pez P�rez - 1890-20-11489"
git push -u origin feature/mercedes-lopez-parser-ast
```

---

### PR #4 � Josu� Morales (Sem�ntica + s�mbolos + injection)

#### Acad�mico

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

Responsable: Josu� David Morales Ram�rez - 1890-23-10545"
git push -u origin feature/josue-morales-semantic
```

---

### PR #5 � Joshua Garc�a (QA / Testing)

#### Acad�mico

```powershell
New-Item -ItemType Directory -Force -Path "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler"
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\LexerTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\ParserTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
Copy-Item "$ACAD\src\test\java\com\qwerys\compiler\SemanticTest.java" "$FORK\docs\java-compiler\src\test\java\com\qwerys\compiler\" -Force
```

#### Producto � tests principales del pipeline compilador

```powershell
$TST = "$FORK\backend\qwerys-backend\src\test\java\com\qwerys\qwerys_backend"
New-Item -ItemType Directory -Force -Path "$TST\analyzer"
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SqlLexerTest.java" "$TST\analyzer\" -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SqlParserTest.java" "$TST\analyzer\" -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\SchemaAwareSemanticAnalyzerTest.java" "$TST\analyzer\" -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\analyzer\ProceduralSemanticAnalyzerTest.java" "$TST\analyzer\" -Force
Copy-Item "$BE\src\test\java\com\qwerys\qwerys_backend\optimization" "$TST\optimization" -Recurse -Force
```

Opcional: a�adir en el PR un `docs/INFORME_MIGRACION.md` (1�2 p�ginas) con captura de `mvn test` BUILD SUCCESS.

```powershell
cd "$FORK\docs\java-compiler"
mvn test
cd "$FORK\backend\qwerys-backend"
.\mvnw.cmd test
git commit -m "test: suite JUnit acad�mica + tests pipeline QWERYS (Fase 5)

Responsable: Joshua Eduardo Garc�a Reyes - 1890-22-5831"
git push -u origin feature/joshua-garcia-testing
```

---

## 9. PASO 3 � Pull Request (todos)

1. Fork propio ? rama propia ? push  
2. GitHub ? **Compare & pull request**  
3. **Base:** `Azucena17/REFACTORIZACION-_C-_-JAVA` ? `main`  
4. **Head:** tu fork ? tu rama  

### Plantilla PR

```markdown
## Integrante
[Nombre] � [Carn�]

## Fase / Rol Gantt
(ej. Fase 2 � Analizador l�xico)

## Archivos producto QWERYS
- (lista rutas backend/frontend)

## Archivos acad�micos
- docs/java-compiler/...

## C�mo probar
cd docs/java-compiler && mvn test
cd backend/qwerys-backend && ./mvnw test
docker compose up --build  (solo cuando main tenga todo mergeado)

## Correspondencia C++ ? Java
Lexer.cpp ? Lexer.java / SqlLexer.java

## Revisores
@SamGMorales @Azucena17
```

**Token GitHub:** solo si `git push` pide autenticaci�n (Settings ? Developer settings ? PAT classic ? scope `repo`).

---

## 10. Verificaci�n FINAL (cuando los 5 PR est�n mergeados en main)

```powershell
git clone https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
copy .env.example .env
# JWT_SECRET m�nimo 32 caracteres
docker compose up --build
```

? http://localhost � **misma demo que el repo del equipo**.

```powershell
cd docs/java-compiler
mvn test
```

? BUILD SUCCESS � compilador acad�mico.

En GitHub ? **Insights ? Contributors** deben aparecer **5 integrantes**.

---

## 11. Exposici�n � qu� decir si preguntan d�nde est� el trabajo

> �Desarrollamos QWERYS en el repo del equipo para integraci�n continua. La entrega calificada est� en el repo del curso: cada integrante abri� su PR con su m�dulo seg�n el Gantt � arquitectura, l�xico, parser, sem�ntica y QA. Al clonar el repo del profesor y levantar Docker, obtienen el mismo sistema que presentamos.�

**No digas:** Swagger, WebSocket, 13 patrones, codegen bytecode, cobertura 75% sin JaCoCo.

---

## 12. Arquitectura (demo oral)

```
Angular + Monaco ? POST /api/queries/analyze
    ? QueryAnalysisService
    ? SqlLexer          (Juanita)
    ? SqlParser         (Mercedes)
    ? SemanticAnalyzer  (Josu�)
    ? OptimizationEngine (Marjorie � 18 reglas)
    ? JSON ? frontend
```

---

## 13. Plan estudio 2 d�as (todos)

| D�a | Ma�ana | Tarde |
|-----|--------|-------|
| 1 | Tus archivos en `docs/java-compiler/` | Equivalentes en `backend/.../analyzer/` + repo C++ |
| 2 | Prompt IA tutor (abajo) | Simular oral + Docker |

### Prompts IA por integrante

Ver secciones anteriores en [`ENTREGA_REPO_PROFESOR.md`](ENTREGA_REPO_PROFESOR.md) � prompts de Marjorie, Juanita, Mercedes, Josu� y Joshua.

---

## 14. Checklist antes de cada PR

- [ ] Rama con nombre exacto (secci�n 4)
- [ ] `git config user.name` es el tuyo
- [ ] Solo **tus** archivos en el commit
- [ ] Copiaste desde `$REF` con `git pull` reciente
- [ ] Acad�mico en `docs/java-compiler/` (no suelto en ra�z)
- [ ] PR con descripci�n completa
- [ ] Otro integrante revisa antes del merge
- [ ] Sin `.env` / `application.properties` / API keys

---

## 15. Errores frecuentes (corregidos)

| Error | Verdad / soluci�n |
|-------|-------------------|
| Solo subir `docs/java-compiler` suelto en ra�z | Estructura monorepo completa (secci�n 0) |
| Todos push desde una cuenta | Cada quien desde **su** GitHub |
| `.\mvnw.cmd` en `docs/java-compiler` | Usar `mvn compile` ah� |
| `\!` y `<\!--` en acad�mico | Corregido en repo del equipo � `git pull` |
| �13 injection / Swagger / WebSocket� | **No existen** � ver secci�n 5 |
| Fork �copy one branch only� | **Desmarcar** � necesitas `main` |

---

## 16. Aviso al equipo � acci�n inmediata

Si alguien ya sigui� la **gu�a antigua** (solo acad�mico en ra�z):

1. **Detener** PRs incompletos.  
2. Leer **esta gu�a** desde secci�n 0.  
3. Marjorie: PR #1b con monorepo (secci�n 8 PR #1).  
4. Dem�s: esperar merge Marjorie ? sus PR con **acad�mico + producto**.  
5. Cada quien desde **su cuenta GitHub**.

---

*QWERYS � Compiladores UMG 2026 � Gu�a definitiva � entrega completa al repo del profesor.*
