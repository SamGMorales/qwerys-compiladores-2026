# QWERYS — SQL/NoSQL Query Analyzer

Migración y extensión del compilador SQL de C++ a Java.  
Curso de Compiladores 2026 — Universidad Mariano Gálvez de Guatemala.

Repositorio de referencia (C++ original): [compilador-sql-final](https://github.com/compilations-teams/compilador-sql-final.git)

---

## ¿Qué es esto?

Sistema web completo que incluye:

- **Compilador SQL en Java** — análisis léxico, sintáctico y semántico (migración del esqueleto C++ del curso)
- **Análisis multi-motor** — SQL (MySQL, PostgreSQL, SQL Server, Oracle, SQLite) y NoSQL (MongoDB, Redis, Elasticsearch, Cassandra, DynamoDB)
- **Detección de SQL Injection** — 5 patrones heurísticos en el analizador semántico (`SE007`)
- **Motor de optimización** — 18 reglas AST sobre SQL y SQL procedural
- **Migración de código entre lenguajes** — módulo aparte (C++/Python/Java/TypeScript; no es la migración del compilador SQL)
- **Frontend Angular 17** con Monaco Editor
- **Análisis asistido por IA** — Groq (primario) y OpenRouter (fallback opcional)
- **PWA instalable**, diseño responsivo y modos de accesibilidad (incl. modo ciego)

### Pipeline del compilador (migración C++ ? Java)

```
SQL ? SqlLexer ? SqlParser ? AstNode ? SemanticAnalyzer ? OptimizationEngine ? REST API
```

| Componente C++ (`compilador-sql-final`) | Equivalente Java (`backend/qwerys-backend`) |
|----------------------------------------|---------------------------------------------|
| `Lexer.cpp` / `Token.h`                | `analyzer/SqlLexer.java`, `Token.java`      |
| `Parser.cpp` / `AST.h`                 | `analyzer/SqlParser.java`, `AstNode.java`   |
| `SemanticAnalyzer.cpp`                 | `analyzer/SemanticAnalyzer.java`            |
| `SymbolTable.cpp`                      | Validación con schema (adapters + reglas)   |
| `main.cpp`                             | `QueryAnalysisService` + `QueryController`    |

El compilador C++ de referencia implementa **SELECT** con esquema fijo; la versión Java conserva la misma arquitectura por fases y la extiende a DML/DDL, múltiples dialectos, NoSQL y API REST — sin JNI ni código nativo.

---

## Requisitos previos

- **Java 17**
- **Node.js 20** (frontend local)
- **Docker Desktop** (opción recomendada)
- **Maven** — incluido como wrapper (`mvnw` / `mvnw.cmd`)

---

## Opción 1 — Correr con Docker (recomendado)

1. Clonar el repositorio:

   ```bash
   git clone <URL_DEL_REPO>
   cd qwerys-project
   ```

2. Copiar la plantilla de configuración:

   ```bash
   cp .env.example .env
   ```

   En Windows (PowerShell):

   ```powershell
   copy .env.example .env
   ```

3. Editar `.env` y completar:

   | Variable | Descripción |
   |----------|-------------|
   | `MYSQL_ROOT_PASSWORD` | Contraseña root de MySQL en Docker |
   | `POSTGRES_PASSWORD` | Contraseña de PostgreSQL en Docker |
   | `JWT_SECRET` | Texto aleatorio de **mínimo 32 caracteres** |
   | `AI_API_KEY` | API key de Groq (opcional; sin ella funciona el análisis por reglas) |
   | `AI_FALLBACK_API_KEY` | API key de OpenRouter (opcional) |

4. Levantar todos los servicios:

   ```bash
   docker compose up --build
   ```

5. Abrir en el navegador: **http://localhost**

   Puertos en el host (por si ya tienes servicios locales): backend `8081`, MySQL `3307`, PostgreSQL `5433`, MongoDB `27018`.

   Más detalle: [`docs/DOCKER_SETUP.md`](docs/DOCKER_SETUP.md)

---

## Opción 2 — Correr local (sin Docker)

### Backend

1. Entrar al directorio del backend:

   ```bash
   cd backend/qwerys-backend
   ```

2. Copiar la plantilla de configuración:

   ```bash
   cp src/main/resources/application.properties.example src/main/resources/application.properties
   ```

3. Editar `application.properties` con tus credenciales locales de MySQL, `JWT_SECRET` y (opcional) las API keys de IA.

4. Ejecutar:

   ```bash
   ./mvnw spring-boot:run
   ```

   En Windows:

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

   El backend queda en **http://localhost:8080**.

### Frontend

1. Entrar al directorio del frontend:

   ```bash
   cd frontend/qwerys-frontend
   ```

2. Instalar dependencias y levantar el servidor de desarrollo:

   ```bash
   npm install
   ng serve
   ```

3. Abrir: **http://localhost:4200**

---

## Tests

Desde `backend/qwerys-backend/`:

```bash
./mvnw test
```

En Windows: `.\mvnw.cmd test`

La suite incluye tests del lexer/parser SQL, analizadores por motor, optimización, IA (con fallback offline) y carga del contexto Spring.

---

## Estructura del proyecto

```
qwerys-project/
??? backend/qwerys-backend/          ? Spring Boot 3.5 / Java 17
?   ??? src/main/java/com/qwerys/qwerys_backend/
?       ??? analyzer/                ? Lexer, Parser, AST, SemanticAnalyzer (migración C++)
?       ??? optimization/            ? Motor de 18 reglas de optimización
?       ??? migration/               ? Conversión de código entre lenguajes (feature aparte)
?       ??? ai/                      ? Integración Groq / OpenRouter
?       ??? adapter/                 ? Adaptadores JDBC/API para 10 motores de BD
??? frontend/qwerys-frontend/        ? Angular 17 + Monaco Editor
?   ??? src/app/
?       ??? features/                ? Módulos principales de la app
?       ??? core/services/           ? Servicios Angular
??? docker-compose.yml               ? Levanta MySQL, PostgreSQL, MongoDB, backend y frontend
??? .env.example                     ? Plantilla de configuración Docker (copiar a .env)
??? docs/                            ? Guías del proyecto (Docker, getting started, etc.)
```

---

## Notas de seguridad

- `application.properties` está en `.gitignore` — **nunca** subir al repositorio
- `.env` está en `.gitignore` — **nunca** subir al repositorio
- Las API keys de IA se comparten solo por canal privado
- Usar `application.properties.example` y `.env.example` como plantillas

---

## Equipo

| Integrante | Rol | Responsabilidad |
|------------|-----|-----------------|
| Marjorie Girón (Arquitecto) | Arquitectura | Spring Boot, Angular, Docker |
| Juanita Raguex (C++ Senior) | Compiladores | Análisis léxico |
| Mercedes López (C++ Junior) | Compiladores | Análisis sintáctico |
| Josué Morales (Java Senior) | Compiladores | Análisis semántico, tabla de símbolos |
| Joshua Garcia (QA) | Calidad | Tests, documentación |
