# Guía definitiva por integrante — entrega académica al repo del profesor

**Universidad Mariano Gálvez · Compiladores · Ing. Richard Ortiz · Ciclo 2026 Sección A**  
**Entrega:** sábado 23 de mayo de 2026, 23:59

> **Para entrega COMPLETA (monorepo + Docker), usa [`GUIA_ENTREGA_COMPLETA_INTEGRANTES.md`](GUIA_ENTREGA_COMPLETA_INTEGRANTES.md)**

> **Este documento** = capa académica en `docs/java-compiler/` solamente.

Este documento **fusiona y corrige** las guías anteriores (`GUIA_INTEGRANTES.md` y `ENTREGA_REPO_PROFESOR.md`).  
Úsalo como **fuente principal**. Las otras guías quedan como referencia histórica.

| Documento | Uso |
|-----------|-----|
| **Este archivo** | Qué sube cada quien al repo del profesor + cómo estudiar en 2 días |
| [`../GUIA_EQUIPO.md`](../GUIA_EQUIPO.md) | Clonar y **correr** QWERYS (Docker / local) |
| [`ENTREGA_REPO_PROFESOR.md`](ENTREGA_REPO_PROFESOR.md) | Detalle académico del módulo `java-compiler` |
| [`GUIA_INTEGRANTES.md`](GUIA_INTEGRANTES.md) | Borrador anterior (contiene errores  no usar solo) |

---

## 1. Qué debes hacer (resumen en 30 segundos)

El código **ya existe y funciona**. Tu trabajo:

1. **Entender** tu módulo como si lo hubieras hecho tú.
2. **Subir** tus archivos al repo del **profesor** en **tu rama** + **Pull Request**.
3. **Presentar** tu parte si el catedrático pregunta.

**No inventes features que no existen** (Swagger, WebSocket, 13 patrones injection, etc.)  ver sección 2.

---

## 2. Tres repositorios  no confundirlos

| Repositorio | URL | Para qué |
|-------------|-----|----------|
| **Correr la app (equipo)** | https://github.com/SamGMorales/qwerys-compiladores-2026 | Proyecto completo: backend, frontend, Docker |
| **Entrega calificada (profesor)** | https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA | Cada integrante abre PR con **su** módulo |
| **Referencia C++ (estudio)** | https://github.com/compilations-teams/compilador-sql-final | Compilador original del curso (solo SELECT) |

> **Regla del profesor:** nadie hace push directo a `main`. Siempre: **rama → PR → revisión → merge**.

---

## 3. Plan Gantt vs. código real (memorizar esto)

| Plan original (PDF) | Lo que QWERYS tiene hoy | Qué decir en la entrega |
|---------------------|-------------------------|-------------------------|
| `Lexer.java` | `SqlLexer.java` + lexers NoSQL | Académico: `docs/java-compiler/Lexer.java` |
| `Parser.java` + `ASTBuilder.java` | `SqlParser.java` + `AstNode.java` | **No hay** `ASTBuilder`  el parser arma el AST |
| `SymbolTable.java` fija | Schema live + `SemanticAnalyzer` | Evolucionamos tabla de símbolos |
| `IntermediateCodeGenerator.java` | **No existe** | Lo reemplazamos por sugerencias (`OptimizationEngine`) |
| 10 reglas optimización | **18 reglas** | Más completo que el plan |
| 13 patrones SQL Injection | **5 patrones** (`SE007`) | Integrado en semántica |
| WebSocket tiempo real | **No**  REST `POST /api/queries/analyze` | |
| Swagger | **No implementado** | API en README + controllers |
| `IntegrationTest.java` | ~63 archivos de test | Nombres distintos, suite amplia |

### Dos capas de código

| Capa | Ubicación | Entrega al profesor |
|------|-----------|---------------------|
| **Académica** (nombres del plan) | `docs/java-compiler/` | **Principal**  cada quien sube sus `.java` + tests |
| **Producto** (QWERYS completo) | `backend/` + `frontend/` | Opcional  PR de integración coordinado por Marjorie |

---

## 4. Equipo y ramas Git (nombres exactos)

| # | Integrante | Carné | Rama Git |
|---|------------|-------|----------|
| 1 | Marjorie Samantha Girón Morales | 1890-22-19957 | `feature/marjorie-giron-arquitectura` |
| 2 | Juanita Raguex Tzum | 1890-20-544 | `feature/juanita-raguex-lexer` |
| 3 | Mercedes Azucena López Pérez | 1890-20-11489 | `feature/mercedes-lopez-parser-ast` |
| 4 | Josué David Morales Ramírez | 1890-23-10545 | `feature/josue-morales-semantic` |
| 5 | Joshua Eduardo García Reyes | 1890-22-5831 | `feature/joshua-garcia-testing` |

### Orden de PRs (obligatorio)

| Orden | Quién | Por qué |
|-------|-------|---------|
| 1 | Marjorie | Crea `pom.xml`, carpetas, `Token.java`, `Main.java` |
| 2 | Juanita | Lexer depende de tokens |
| 3 | Mercedes | Parser depende de lexer |
| 4 | Josué | Semántica depende del AST |
| 5 | Joshua | Tests sobre todo lo anterior |

---

## 5. PASO 0  Configurar entorno (todos)

### Herramientas

| Herramienta | Enlace | Nota |
|-------------|--------|------|
| Git | https://git-scm.com/download/win | Opciones por defecto |
| Java 17 | https://adoptium.net | Marcar "Set JAVA_HOME" |
| Node.js 20 | https://nodejs.org | El Dockerfile usa Node 20 |
| Docker Desktop | https://www.docker.com/products/docker-desktop | Para correr la app completa |

**Maven:** no es obligatorio instalarlo  el proyecto trae `mvnw` / `mvnw.cmd`. Si quieres Maven global: https://maven.apache.org/download.cgi

### Verificar instalación

```powershell
git --version
java -version
node --version
docker --version
```

### Configurar Git con TU identidad (crítico para la nota)

```powershell
git config --global user.name "Tu Nombre Completo"
git config --global user.email "tu-email-de-github@ejemplo.com"
```

El profesor verá **quién** hizo cada commit. Usa **tu** nombre, no el de un compañero.

---

## 6. PASO 1  Obtener el código (todos)

### 6.1 Fork del repo del profesor

1. Abre: https://github.com/Azucena17/REFACTORIZACION-_C-_-JAVA  
2. Clic **Fork** → créalo en **tu cuenta**  
3. Tu URL será: `https://github.com/TU-USUARIO/REFACTORIZACION-_C-_-JAVA`

### 6.2 Clonar TU fork

```powershell
cd $HOME\Documents
git clone https://github.com/TU-USUARIO/REFACTORIZACION-_C-_-JAVA.git
cd REFACTORIZACION-_C-_-JAVA
```

### 6.3 Clonar repo del equipo (código fuente para copiar)

```powershell
cd $HOME\Documents
git clone https://github.com/SamGMorales/qwerys-compiladores-2026.git
```

En adelante:

- `$REF` = ruta a `qwerys-compiladores-2026`
- `$FORK` = ruta a tu `REFACTORIZACION-_C-_-JAVA`

### 6.4 Crear tu rama

```powershell
cd $FORK
git checkout -b feature/TU-RAMA
```

(Usa el nombre exacto de la tabla de la sección 4.)

---

## 7. PASO 2  Qué sube cada integrante

**Origen académico (todos):** `$REF\docs\java-compiler\`  
**Destino en tu fork:** `$FORK\` (misma estructura `src/main/java/com/qwerys/compiler/`)

Crea carpetas si no existen:

```powershell
mkdir -Force "$FORK\src\main\java\com\qwerys\compiler"
mkdir -Force "$FORK\src\test\java\com\qwerys\compiler"
```

---

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
2) Mapeo a query1.sql 
 query_error3.sql del repo C++ compilador-sql-final.
3) Informe técnico: migración C++, beneficios Java, desviaciones (18 reglas, 5 injection, sin WebSocket/Swagger/codegen).
4) 10 preguntas del profesor sobre TDD con respuestas modelo.

No inventes SemanticAnalyzerTest.java ni cobertura 75% medida  no tenemos JaCoCo configurado.
```

---

## 8. PASO 3  Push y Pull Request (todos)

### 8.1 Push

```powershell
git push -u origin feature/TU-RAMA
```

**Autenticación GitHub (HTTPS):**

- Usuario: tu username de GitHub  
- Contraseña: **Personal Access Token** (no tu contraseña normal)

**Crear token:** GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate → marcar **repo** → copiar y guardar.

### 8.2 Abrir Pull Request

1. Ve a tu fork en GitHub.  
2. **Compare & pull request**  
3. **Base:** `Azucena17/REFACTORIZACION-_C-_-JAVA` → `main`  
4. **Head:** tu fork → tu rama  
5. Usa esta plantilla en la descripción:

```markdown
## Integrante
[Nombre]  [Carné]

## Fase
Fase X  [Nombre fase]

## Archivos
- (lista exacta)

## Cómo probar
cd repo && mvn test

## Correspondencia C++ → Java
- Lexer.cpp → Lexer.java (ejemplo)

## Revisores
@SamGMorales
```

6. **Create pull request**  espera revisión antes de merge.

---

## 9. Correr QWERYS para estudiar (repo del equipo)

Ver [`../GUIA_EQUIPO.md`](../GUIA_EQUIPO.md). Resumen:

**Docker (recomendado):**

```powershell
cd $HOME\Documents\qwerys-compiladores-2026
copy .env.example .env
# Editar JWT_SECRET (+ pedir AI_API_KEY a Marjorie por privado)
docker compose up --build
```

→ http://localhost

**Local:**

```powershell
# Backend
cd qwerys-compiladores-2026\backend\qwerys-backend
copy src\main\resources\application.properties.example src\main\resources\application.properties
.\mvnw.cmd spring-boot:run

# Frontend (otra terminal)
cd qwerys-compiladores-2026\frontend\qwerys-frontend
npm install
ng serve
```

→ http://localhost:4200

---

## 10. Arquitectura real (para la demo oral)

```
Usuario escribe SQL en Angular + Monaco Editor
         →  HTTP POST /api/queries/analyze  (NO WebSocket)
    QueryAnalysisService.java
         →
    SqlLexer.java              → Juanita (léxico)
         →
    SqlParser.java → AstNode   → Mercedes (sintáctico)
         →
    SemanticAnalyzer.java      → Josué (semántica + 5 patrones SE007)
         →
    OptimizationEngine.java    → Marjorie (18 reglas)
         →
    AiSuggestionService        → opcional (Groq, usuario registrado)
         →
    JSON al frontend
```

**Puertos:** Docker frontend :80, backend host :8081; local backend :8080, Angular :4200.

---

## 11. Plan de estudio 2 días (todos)

| Día | Mañana | Tarde |
|-----|--------|-------|
| **Día 1** | Leer tus archivos académicos en `docs/java-compiler/` | Leer equivalentes en `backend/.../analyzer/` + repo C++ |
| **Día 2** | Prompt IA tutor (sección 7) + pegar tus `.java` | Simular examen oral en voz alta; correr app con Docker |

---

## 12. Checklist antes de cada PR

- [ ] Rama con nombre exacto de la sección 4  
- [ ] `git config user.name` es el tuyo  
- [ ] Solo **tus** archivos en el commit  
- [ ] `mvn test` pasa (después del PR #1 de Marjorie)  
- [ ] PR con descripción completa  
- [ ] Otro integrante revisa  
- [ ] **No** subiste `.env`, `application.properties`, ni API keys  
- [ ] **No** prometes Swagger, WebSocket, 13 injection, ni cobertura medida sin evidencia  

---

## 13. Errores que NO debes repetir (corregidos respecto a GUIA_INTEGRANTES.md)

| Error en borrador anterior | Verdad |
|----------------------------|--------|
| Rutas tests sin `analyzer/` | Tests en `.../analyzer/SqlLexerTest.java` |
| Falta sección Marjorie | Ella va **PR #1** |
| Josué copia toda carpeta `model/` | Solo archivos semánticos académicos |
| Joshua copia todo `src/test/` al inicio | Solo 3 tests académicos; el resto es referencia |
| "13 patrones injection" | **5 patrones** |
| "10 reglas optimización" | **18 reglas** |
| WebSocket | **REST** |
| Swagger | **No implementado** |
| SqlLexerTest "15+ casos" | **1 test** |
| SemanticAnalyzerTest.java | **No existe** |

---

## 14. Documentos relacionados

| Archivo | Contenido |
|---------|-----------|
| [`ENTREGA_REPO_PROFESOR.md`](ENTREGA_REPO_PROFESOR.md) | Detalle académico extendido |
| [`../GUIA_EQUIPO.md`](../GUIA_EQUIPO.md) | Clonar y ejecutar la app |
| [`GUIA_INTEGRANTES.md`](GUIA_INTEGRANTES.md) | Borrador Claude (con errores) |
| `docs/java-compiler/README.md` | Mapa archivos → integrantes |

---

*QWERYS · Compiladores UMG 2026 · Guía definitiva corregida al estado final del código.*