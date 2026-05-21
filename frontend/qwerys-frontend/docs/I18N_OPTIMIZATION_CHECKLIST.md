# Checklist: nueva regla en `OptimizationEngine`

Cuando añadas o modifiques una **`OptimizationRule`** en el backend (`qwerys-backend`, paquete `optimization`):

1. **`getRuleId()`** debe devolver un identificador estable con prefijo **`OPT-`** (por ejemplo `OPT-012`, `OPT-PROC-007`).
2. En la **misma PR** (o commit ligado), actualiza **`src/assets/i18n/es.json`** y **`src/assets/i18n/en.json`** dentro de **`analyzer.optimizations.<ruleId>`**:
   - **`description`** (obligatorio): texto claro para el usuario, en el idioma del archivo.
   - Si la regla es de tipo procedural con recuadros Original/Optimizado localizados (`OPT-PROC-*` o `OPT-CURSOR-LOOP`): añade también **`original`** y **`optimized`** (obligatorio).
3. Ejecuta localmente:

   ```bash
   node scripts/check-optimization-i18n.mjs
   ```

   Debe imprimir `OK` y salir con código 0.

4. La CI del frontend ejecuta este script en cada push/PR; si falta una clave, el job falla.

### Repositorios separados (GitHub Actions)

El workflow `.github/workflows/i18n-optimization-sync.yml` clona **`${owner}/qwerys-backend`** junto al frontend para ejecutar el script. Si el backend tiene otro nombre o es privado sin permisos, ajusta el workflow o mantén el **layout local monorepo** (`qwerys-project/backend/qwerys-backend`) y ejecuta `npm run check:i18n-optimizations` antes de hacer push.

**Nota:** El analizador usa fallback a la `description` del backend si falta la entrada i18n, pero la CI exige sincronización para no volver a mostrar claves crudas ni mezclas inconsistentes entre idiomas.
