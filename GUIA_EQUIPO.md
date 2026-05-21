# Guía para el equipo — clonar y correr QWERYS

**Repositorio:** https://github.com/SamGMorales/qwerys-compiladores-2026

Al clonar, Git crea la carpeta `qwerys-compiladores-2026`. No hace falta crear carpetas extra: solo copiar los archivos de configuración en las rutas exactas indicadas abajo.

---

## ¿Dónde van los archivos de acceso?

| Archivo | ¿Cuándo se usa? | Ubicación exacta |
|---------|-----------------|------------------|
| `.env` | Solo con **Docker** | Raíz del repo (`qwerys-compiladores-2026/.env`) |
| `application.properties` | Solo **local sin Docker** | `backend/qwerys-backend/src/main/resources/application.properties` |

> **Nunca subir** `.env` ni `application.properties` a GitHub. Las API keys se comparten solo por canal privado (WhatsApp/Telegram).

Estructura de referencia:

```
qwerys-compiladores-2026/
??? .env                          ? Docker (copiar desde .env.example)
??? .env.example
??? docker-compose.yml
??? backend/
?   ??? qwerys-backend/
?       ??? src/main/resources/
?           ??? application.properties.example
?           ??? application.properties   ? Local sin Docker
??? frontend/
    ??? qwerys-frontend/
```

---

## Opción A — Docker (recomendada)

**Requisito:** Docker Desktop instalado y en ejecución.

### 1. Clonar

```powershell
git clone https://github.com/SamGMorales/qwerys-compiladores-2026.git
cd qwerys-compiladores-2026
```

### 2. Crear `.env` en la raíz

Mismo nivel que `docker-compose.yml`:

```powershell
copy .env.example .env
```

Mac/Linux:

```bash
cp .env.example .env
```

### 3. Editar `.env`

| Variable | Qué poner |
|----------|-----------|
| `MYSQL_ROOT_PASSWORD` | Cualquier contraseña (ej. `qwerys2024`) |
| `POSTGRES_PASSWORD` | Cualquier contraseña (ej. `root`) |
| `JWT_SECRET` | Texto aleatorio de **mínimo 32 caracteres** |
| `AI_API_KEY` | Key de Groq — pedir al líder (opcional; sin ella funciona el analizador por reglas) |
| `AI_FALLBACK_API_KEY` | OpenRouter — opcional |

### 4. Levantar todo

```powershell
docker compose up --build
```

La primera vez puede tardar varios minutos (descarga imágenes, compila backend y frontend).

### 5. Abrir la aplicación

Navegador: **http://localhost**

- **“Continuar como Invitado”** — probar el analizador SQL/NoSQL.
- **Registrarse** — necesario para funciones de IA.
- Si el puerto 80 está ocupado, cambiar en `docker-compose.yml` a `"8080:80"` y usar http://localhost:8080.

> Con Docker **no necesitan** crear `application.properties`. El backend usa el perfil `docker` y lee los secretos del `.env`.

---

## Opción B — Local sin Docker

**Requisitos:** Java 17, Node.js 20, MySQL local con base de datos `qwerys`.

### 1. Clonar

Igual que en la Opción A.

### 2. Crear `application.properties`

Desde la raíz del repo:

Windows:

```powershell
copy backend\qwerys-backend\src\main\resources\application.properties.example backend\qwerys-backend\src\main\resources\application.properties
```

Mac/Linux:

```bash
cp backend/qwerys-backend/src/main/resources/application.properties.example backend/qwerys-backend/src/main/resources/application.properties
```

### 3. Editar `application.properties`

Completar al menos:

| Propiedad | Qué poner |
|-----------|-----------|
| `spring.datasource.username` | Usuario MySQL local |
| `spring.datasource.password` | Contraseña MySQL local |
| `jwt.secret` | Texto largo, mínimo 32 caracteres |
| `ai.api-key` | Key de Groq — pedir al líder (opcional) |
| `ai.fallback-api-key` | OpenRouter — opcional |

MySQL debe estar en `localhost:3306` con la base `qwerys`. Spring crea/actualiza tablas automáticamente (`ddl-auto=update`).

### 4. Backend (terminal 1)

```powershell
cd backend\qwerys-backend
.\mvnw.cmd spring-boot:run
```

Mac/Linux: `./mvnw spring-boot:run`

Backend: **http://localhost:8080**

### 5. Frontend (terminal 2)

```powershell
cd frontend\qwerys-frontend
npm install
ng serve
```

Frontend: **http://localhost:4200**

> En local **no hace falta** `.env` en la raíz; los secretos van en `application.properties`.

---

## Qué pedir al líder del proyecto

Por canal privado (nunca por git):

1. `AI_API_KEY` (Groq)
2. `AI_FALLBACK_API_KEY` (OpenRouter) — opcional
3. `JWT_SECRET` compartido — opcional, para que todos usen el mismo en desarrollo

---

## Checklist rápido

### Docker

- [ ] Clonar repo
- [ ] `copy .env.example .env` en la **raíz**
- [ ] Editar `.env` (JWT + API key si aplica)
- [ ] `docker compose up --build`
- [ ] Abrir http://localhost

### Local

- [ ] Clonar repo
- [ ] Copiar `application.properties.example` ? `application.properties` en `src/main/resources/`
- [ ] Tener MySQL corriendo con BD `qwerys`
- [ ] `mvnw spring-boot:run` en backend
- [ ] `npm install` + `ng serve` en frontend
- [ ] Abrir http://localhost:4200

---

## Problemas frecuentes

| Problema | Solución |
|----------|----------|
| Puerto 80 ocupado (Docker) | Cambiar en `docker-compose.yml` a `"8080:80"` y usar http://localhost:8080 |
| Backend no conecta a MySQL (local) | Verificar que MySQL corre en 3306 y que existe la BD `qwerys` |
| IA no responde | Pedir `AI_API_KEY` al líder; sin key el analizador por reglas sigue funcionando |
| Modo invitado sin IA | Crear cuenta — la IA requiere usuario registrado |
