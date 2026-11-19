# Prompt maestro — Enjambre de agentes para MS-TRANSFER-LIMITS

> Este prompt lo ejecuta un agente **orquestador** (Claude Code, modelo Opus 5.5) que coordina un
> enjambre de sub-agentes especializados y usa servidores **MCP** para obtener documentación vigente,
> operar GitHub y verificar la base de datos. Está escrito para copiarse tal cual en Claude Code
> desde `U:\Edgar\Estudio\Banamex`.

---

## 0. Rol y misión del orquestador

Eres el **orquestador** de un enjambre de agentes de ingeniería. Tu misión es entregar, en el
repositorio `https://github.com/edgarcdmx/MS-TRANSFER-LIMITS` (copia local en
`U:\Edgar\Estudio\Banamex`), dos cosas que se evalúan por igual:

1. **El microservicio** `MS-TRANSFER-LIMITS` (Java + Spring Boot, Maven, PostgreSQL).
2. **El harness** que protege el código y guía a los agentes: `CLAUDE.md`, `.claude/settings.json`,
   skills propias, un subagente revisor de calidad y vulnerabilidades, hooks que bloquean el push a
   GitHub si algo falla, y configuración MCP (`.mcp.json`).

Reglas del orquestador:

- **No escribes código de producción tú mismo** salvo para integrar; delegas en los agentes de la
  sección 3 y verificas su salida.
- Cada agente recibe un **brief autocontenido** (no hereda tu contexto): objetivo, archivos que le
  pertenecen, archivos que NO debe tocar, contrato compartido, criterios de aceptación y formato de
  reporte.
- Los agentes de la fase paralela trabajan en **worktrees aislados** (`isolation: "worktree"`) y
  sólo tocan su carpeta de propiedad; tú integras en `main`.
- Nada se da por terminado sin evidencia: salida de `mvnw verify`, reporte JaCoCo, veredicto del
  subagente revisor.
- No publiques secretos. Tokens sólo por variables de entorno (`GITHUB_PERSONAL_ACCESS_TOKEN`), nunca
  en archivos versionados.
- Idioma: código, identificadores y commits en **inglés**; README y documentación en **español**.

---

## 1. Hechos del entorno (verificados el 2026-11-19)

| Elemento | Estado | Acción |
|---|---|---|
| SO | Windows 11, Git Bash + PowerShell 5.1 | Scripts de hooks en **bash** (Git Bash) |
| Git | 2.54 instalado, `user.name` configurado | — |
| JDK | **No instalado** | Instalar Temurin **27** portable en `%USERPROFILE%\.local\devtools\jdk` (fuera del repo) |
| Maven | **No instalado** | Usar **Maven Wrapper** (`mvnw`/`mvnw.cmd`) con Maven **3.9.16** |
| GitHub CLI | **No instalado** | Preferir **GitHub MCP**; `gh` portable como respaldo |
| Docker | **No instalado** | Generar `Dockerfile` + `compose.yaml`; documentar que la verificación de imagen queda para el usuario |
| Carpeta destino | Vacía (sólo este prompt) | Inicializar repo aquí |

Versiones "última disponible" (Maven Central / Adoptium, 2026-11-19). **Re-verificar al iniciar** con
MCP `fetch` contra `https://repo1.maven.org/maven2/<grupo>/<artefacto>/maven-metadata.xml`:

| Componente | Versión |
|---|---|
| Java (Temurin) | 27 (feature más reciente; LTS 25 como respaldo si alguna herramienta no soporta 27) |
| Spring Boot (`spring-boot-starter-parent`) | 4.2.0 |
| Maven | 3.9.16 (vía wrapper) |
| Apache Commons Text | 1.15.0 |
| springdoc-openapi-starter-webmvc-ui | 3.2.0 |
| spotless-maven-plugin (google-java-format) | 3.11.0 |
| checkstyle (google_checks.xml) | 14.4.0 |
| jacoco-maven-plugin | 0.8.16 |

Si una versión resulta incompatible (p. ej. JaCoCo o Mockito con Java 27), **documéntalo en el
README** y baja sólo lo mínimo necesario.

---

## 2. Servidores MCP del enjambre

Crear `.mcp.json` en la raíz (versionado, **sin secretos**, sólo referencias a variables de entorno):

```json
{
  "mcpServers": {
    "github": {
      "type": "http",
      "url": "https://api.githubcopilot.com/mcp/",
      "headers": { "Authorization": "Bearer ${GITHUB_PERSONAL_ACCESS_TOKEN}" }
    },
    "context7": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@upstash/context7-mcp"]
    },
    "fetch": {
      "type": "stdio",
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    },
    "postgres": {
      "type": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-postgres",
               "postgresql://transfer:transfer@localhost:5432/transfer_limits"]
    }
  }
}
```

Uso obligatorio por fase:

| MCP | Quién lo usa | Para qué |
|---|---|---|
| `github` | Orquestador, Agente DevOps | Crear repo `edgarcdmx/MS-TRANSFER-LIMITS` (público o privado según el usuario), proteger `main`, abrir PR final, comprobar estado de push |
| `context7` | Todos los agentes de código | Consultar documentación **vigente** de Spring Boot 4.2, Spring Framework 7, Hibernate 7, springdoc 3, OpenTelemetry starter, Commons Text — no confiar en memoria para APIs que cambiaron en Boot 4 |
| `fetch` | Orquestador, Arquitecto | Re-verificar versiones en Maven Central/Adoptium; leer RFC 9457 |
| `postgres` | Agente Persistencia (opcional) | Si hay PostgreSQL local, validar que las migraciones Flyway aplican y que el `UPDATE` atómico se comporta como se espera. Si no hay BD, omitir y reportarlo |

Si un MCP no conecta, el agente lo reporta y usa la alternativa (CLI `gh`, `curl`), nunca se
inventa resultados.

---

## 3. Topología del enjambre

```
                         ┌──────────────────────────┐
                         │  ORQUESTADOR (Opus 5.5)  │
                         └────────────┬─────────────┘
       Fase 1 (secuencial)            │
                         ┌────────────▼─────────────┐
                         │  A1 Arquitecto/Contrato  │  → openapi.yaml + ADRs + esqueleto Maven
                         └────────────┬─────────────┘
       Fase 2 (paralelo, worktrees)   │
  ┌──────────────┬──────────────┬─────┴────────┬──────────────┬──────────────┐
  ▼              ▼              ▼              ▼              ▼              │
A2 Harness   A3 Dominio+    A4 API web +   A5 Observab.+  A6 Pruebas      │
(.claude,    Persistencia   RFC 9457 +     Docker         unitarias       │
 hooks,MCP)  +Concurrencia  Commons Text                   (≥90%)         │
  └──────────────┴──────────────┴──────────────┴──────────────┘              │
       Fase 3 (integración)                                                  │
                  Orquestador integra → mvnw verify → A7 Revisor (subagente) │
                  ↺ bucle de corrección (máx. 3 iteraciones)                  │
       Fase 4 (entrega)                                                      │
                  A8 Documentación (README) → commit → push (hooks) → GitHub MCP
```

Regla de propiedad de archivos (evita conflictos en paralelo):

| Agente | Es dueño de | No toca |
|---|---|---|
| A1 Arquitecto | `pom.xml`, `src/main/resources/openapi/openapi.yaml`, `docs/adr/`, `mvnw*`, `.mvn/` | código Java |
| A2 Harness | `CLAUDE.md`, `.claude/**`, `.githooks/**`, `scripts/**`, `.mcp.json`, `config/checkstyle/` | `src/**` |
| A3 Dominio+Persistencia | `src/main/java/**/domain/**`, `**/infrastructure/persistence/**`, `src/main/resources/db/migration/**` | web, config OTel |
| A4 API web | `**/api/**` (controllers, DTOs, mappers), `**/application/**` (casos de uso), `**/api/error/**`, `src/main/resources/messages/**` | persistencia |
| A5 Observabilidad+Docker | `application.yml`, `**/config/**`, `Dockerfile`, `compose.yaml`, `.dockerignore` | dominio |
| A6 Pruebas | `src/test/**` | `src/main/**` (si detecta bug, lo reporta al orquestador) |
| A7 Revisor | sólo lectura | todo |
| A8 Documentación | `README.md` | código |

Paquete base: `com.banamex.transferlimits` con arquitectura hexagonal ligera:
`api` → `application` → `domain` ← `infrastructure`.

---

## 4. Especificación funcional (compartida por todos los agentes)

**Nombre:** MS-TRANSFER-LIMITS
**Propósito:** decidir si una transferencia puede hacerse según los límites del cliente, registrar el
consumo del límite diario y rechazar operaciones que lo excedan.

Reglas de negocio:

1. Cada cuenta tiene, **por canal** (`MOBILE_APP`, `WEB`, `BRANCH`), un **límite diario** y un
   **límite por operación**.
2. Una transferencia se **aprueba** sólo si `monto ≤ límite por operación` **y**
   `consumido_hoy + monto ≤ límite diario`. Si se aprueba, el consumo se registra en la misma
   transacción.
3. Montos en `BigDecimal`, escala 2, moneda `MXN`, `NUMERIC(19,2)` en BD; monto > 0.
4. "Día" = fecha de negocio en zona `America/Mexico_City` (configurable), inyectada vía `Clock` para
   poder probarla.
5. Mensajes de rechazo construidos con **Apache Commons Text** (`StringSubstitutor`) a partir de
   plantillas externas (`messages/rejections.properties`), p. ej.:
   `PER_OPERATION_LIMIT_EXCEEDED=El monto ${amount} ${currency} excede el límite por operación de ${perOperationLimit} ${currency} para el canal ${channel}.`
   `DAILY_LIMIT_EXCEEDED=El monto ${amount} ${currency} excede el disponible diario de ${available} ${currency} (límite ${dailyLimit}, consumido ${consumed}) para el canal ${channel}.`
6. Idempotencia: la validación acepta un `transferId` (UUID); repetir el mismo `transferId` devuelve
   el resultado original sin volver a consumir.
7. **Concurrencia:** dos validaciones simultáneas **nunca** pueden dejar el consumido por encima del
   límite diario (ver §6).

Endpoints mínimos (en el contrato se corrige la errata `accoundId` → `accountId`):

| Método | Ruta | Descripción | Respuestas |
|---|---|---|---|
| GET | `/api/v1/accounts/{accountId}/limits` | Límites y consumo del día por canal | 200, 404 |
| PUT | `/api/v1/accounts/{accountId}/limits/{channelId}` | Crea/actualiza límite diario y por operación del canal (valida `perOperationLimit ≤ dailyLimit`) | 200, 400, 422 |
| POST | `/api/v1/accounts/{accountId}/transfer-validations` | Valida y consume límite. Body: `transferId`, `channel`, `amount`, `currency` | 201 aprobada, 422 rechazada (Problem Detail con motivo), 404 sin límites, 400 |
| GET | `/actuator/health` | Salud (incluye `db`) | 200/503 |

Respuesta de validación aprobada: `{ transferId, status: "APPROVED", channel, amount, dailyLimit, consumedToday, availableToday, validatedAt }`.

---

## 5. Requisitos no funcionales (criterios de aceptación globales)

| # | Requisito | Cómo se cumple / verifica |
|---|---|---|
| R1 | Java y Spring Boot última versión | §1; `pom.xml` con `java.version=27`, parent 4.2.0 |
| R2 | Maven | `mvnw` 3.9.16; `./mvnw verify` es el gate único |
| R3 | Estilo **Google Java Style** | Spotless + `google-java-format` (`spotless:check` en `verify`) + Checkstyle `google_checks.xml` con `failOnViolation=true` |
| R4 | Errores **RFC 9457** | `spring.mvc.problemdetails.enabled=true` + `@RestControllerAdvice` que extiende `ResponseEntityExceptionHandler`, `ProblemDetail` con `type` URI (`https://api.banamex.example/problems/<code>`), `title`, `status`, `detail`, `instance`, y extensiones `code`, `traceId`, `errors[]`; `Content-Type: application/problem+json` |
| R5 | Trazas **OpenTelemetry** | `spring-boot-starter-opentelemetry` (Boot 4) / Micrometer Tracing bridge OTel + exportador OTLP; `traceId` en logs y en Problem Details; spans manuales en el caso de uso de validación; endpoint OTLP configurable por `OTEL_EXPORTER_OTLP_ENDPOINT` |
| R6 | Imagen Docker | `Dockerfile` multi-stage (`eclipse-temurin:27-jdk` build → `eclipse-temurin:27-jre` runtime), capas de Spring Boot (`jarmode=tools`), usuario no root, `HEALTHCHECK`; `compose.yaml` con `postgres:18`, app y `otel/opentelemetry-collector` / Jaeger |
| R7 | Cobertura ≥ 90 % | `jacoco:check` en `verify` con regla `LINE` y `BRANCH` ≥ 0.90 (excluir sólo `*Application`, DTO records y config pura, justificándolo) |
| R8 | Concurrencia | §6 |
| R9 | Contrato OpenAPI 3 | **Contract-first**: `openapi.yaml` (OpenAPI 3.1) escrito por A1; servido por springdoc en `/v3/api-docs` y Swagger UI; prueba que compara contrato vs. endpoints |
| R10 | PostgreSQL | Spring Data JPA + Flyway; `ddl-auto=validate` |
| R11 | **Sin** pruebas de integración | Nada de Testcontainers/Failsafe/`@SpringBootTest` con BD. Sólo pruebas unitarias y slices `@WebMvcTest` con mocks |
| R12 | **Sin** escaneo de dependencias | No incluir OWASP dependency-check, Dependabot, Snyk ni similares; declararlo en `CLAUDE.md` y README |
| R13 | Harness | `CLAUDE.md`, `.claude/settings.json`, ≥ 3 skills, 1 subagente revisor, hooks (Claude Code + git `pre-push`), `.mcp.json` |

---

## 6. Diseño de concurrencia (obligatorio para A3)

Esquema (Flyway `V1__init.sql`):

```sql
CREATE TABLE account_channel_limit (
  account_id            VARCHAR(34)    NOT NULL,
  channel               VARCHAR(16)    NOT NULL CHECK (channel IN ('MOBILE_APP','WEB','BRANCH')),
  daily_limit           NUMERIC(19,2)  NOT NULL CHECK (daily_limit >= 0),
  per_operation_limit   NUMERIC(19,2)  NOT NULL CHECK (per_operation_limit >= 0),
  currency              CHAR(3)        NOT NULL DEFAULT 'MXN',
  version               BIGINT         NOT NULL DEFAULT 0,
  updated_at            TIMESTAMPTZ    NOT NULL,
  PRIMARY KEY (account_id, channel),
  CHECK (per_operation_limit <= daily_limit)
);

CREATE TABLE daily_consumption (
  account_id     VARCHAR(34)   NOT NULL,
  channel        VARCHAR(16)   NOT NULL,
  business_date  DATE          NOT NULL,
  consumed       NUMERIC(19,2) NOT NULL DEFAULT 0 CHECK (consumed >= 0),
  PRIMARY KEY (account_id, channel, business_date)
);

CREATE TABLE transfer_validation (
  transfer_id    UUID PRIMARY KEY,
  account_id     VARCHAR(34)   NOT NULL,
  channel        VARCHAR(16)   NOT NULL,
  amount         NUMERIC(19,2) NOT NULL,
  status         VARCHAR(16)   NOT NULL,
  reason_code    VARCHAR(64),
  business_date  DATE          NOT NULL,
  created_at     TIMESTAMPTZ   NOT NULL
);
```

Algoritmo en una sola transacción `@Transactional` (READ COMMITTED basta porque la condición va
dentro de la sentencia atómica):

1. Leer límite del canal → si no existe, 404.
2. Si `amount > per_operation_limit` → rechazo `PER_OPERATION_LIMIT_EXCEEDED` (se registra en
   `transfer_validation`, no consume).
3. `INSERT INTO daily_consumption (...) VALUES (:acc,:ch,:date,0) ON CONFLICT DO NOTHING;`
4. **Consumo atómico condicionado**:
   ```sql
   UPDATE daily_consumption
      SET consumed = consumed + :amount
    WHERE account_id = :acc AND channel = :ch AND business_date = :date
      AND consumed + :amount <= :dailyLimit
   RETURNING consumed;
   ```
   PostgreSQL bloquea la fila durante el `UPDATE`; la segunda transacción concurrente re-evalúa el
   `WHERE` con el valor ya comprometido, por lo que **es imposible exceder el límite**. 0 filas →
   rechazo `DAILY_LIMIT_EXCEEDED`.
5. Insertar `transfer_validation`; violación de PK por `transfer_id` repetido → devolver resultado
   existente (idempotencia).

Prueba unitaria de concurrencia (sin BD real, R11): simular el repositorio con un adaptador en
memoria que reproduce la semántica del `UPDATE` condicionado con `ConcurrentHashMap.compute`, y
lanzar N hilos (`ExecutorService` + `CountDownLatch`) validando que la suma aprobada ≤ límite diario.
Documentar en ADR-002 por qué se eligió `UPDATE` condicionado frente a `SELECT … FOR UPDATE` o
bloqueo optimista.

---

## 7. Briefs de los agentes

Cada brief se entrega tal cual al lanzar el agente (Agent tool, `subagent_type: "general-purpose"`,
salvo A7 que es el subagente propio). Todos terminan con el **formato de reporte**:

```
STATUS: DONE | BLOCKED
FILES: <lista de archivos creados/modificados>
VERIFIED: <comandos ejecutados y resultado>
OPEN ISSUES: <riesgos o pendientes>
```

### A1 — Arquitecto / Contrato (Fase 1, secuencial)

- Re-verifica versiones con MCP `fetch` (§1) y consulta con `context7` los cambios de Spring Boot 4.x
  relevantes (starters renombrados, OpenTelemetry starter, Jackson 3, `spring-boot-starter-webmvc`).
- Crea `pom.xml` con: parent Spring Boot 4.2.0; starters webmvc, validation, data-jpa, actuator,
  opentelemetry; `postgresql` (runtime); `flyway-core` + `flyway-database-postgresql`;
  `commons-text`; `springdoc-openapi-starter-webmvc-ui`; test: `spring-boot-starter-test`.
  Plugins: spotless (googleJavaFormat), checkstyle (google_checks, `violationSeverity=warning`,
  `failOnViolation=true`), jacoco (prepare-agent, report, check 0.90), spring-boot-maven-plugin.
  **Sin** failsafe ni plugins de escaneo de dependencias.
- Genera Maven Wrapper 3.9.16.
- Escribe `openapi.yaml` (OpenAPI 3.1) completo: esquemas `ChannelLimit`, `AccountLimits`,
  `UpdateLimitRequest`, `TransferValidationRequest`, `TransferValidationResponse`, `ProblemDetail`
  (RFC 9457) y ejemplos para cada respuesta.
- ADRs: `ADR-001-arquitectura-hexagonal.md`, `ADR-002-concurrencia.md`,
  `ADR-003-contract-first.md`.
- Criterio: `./mvnw -q validate` pasa; contrato válido (lint con springdoc o `swagger-parser`).

### A2 — Ingeniero de Harness (Fase 2)

Crear:

1. **`CLAUDE.md`**: propósito, arquitectura y paquetes, comandos (`./mvnw spotless:apply`,
   `./mvnw verify`), reglas (Google Java Style, contract-first, RFC 9457, sin pruebas de integración,
   sin escaneo de dependencias, cobertura ≥ 90 %, BigDecimal para dinero, `Clock` inyectado), flujo
   obligatorio antes de push (skill `quality-gate` + subagente revisor), y qué MCP usar para qué.
2. **`.claude/settings.json`**:
   - `permissions.allow`: `Bash(./mvnw *)`, `Bash(mvnw.cmd *)`, `Bash(git status*)`, `Bash(git diff*)`,
     `Bash(git log*)`, `Bash(git add*)`, `Bash(git commit*)`, `mcp__github__*`, `mcp__context7__*`.
   - `permissions.deny`: `Read(./.env*)`, `Bash(git push --force*)`, `Bash(git push -f*)`,
     `Bash(*--no-verify*)`, `Bash(rm -rf *)`.
   - `hooks`:
     - `PreToolUse` matcher `Bash` → `scripts/hooks/pre-push-gate.sh`: si el comando contiene
       `git push`, ejecuta `./mvnw -q -B verify` y verifica que exista
       `target/review/REVIEW_OK` más reciente que el último commit; si falla, `exit 2` con motivo en
       stderr (bloquea la herramienta y se lo explica a Claude).
     - `PreToolUse` matcher `Edit|Write` → `scripts/hooks/protect-files.sh`: bloquea escribir
       `.env`, `*.pem`, `*.key` y archivos bajo `target/`.
     - `PostToolUse` matcher `Edit|Write` → `scripts/hooks/format-java.sh`: si el archivo es `.java`,
       corre `./mvnw -q spotless:apply -DspotlessFiles=<archivo>`.
     - `Stop` → `scripts/hooks/remind-gate.sh`: si hay cambios Java sin `verify` reciente, avisa.
   - `enableAllProjectMcpServers: true`.
3. **Hook de git nativo** `.githooks/pre-push` (activado con `git config core.hooksPath .githooks`,
   documentado en README) que ejecuta el mismo `scripts/quality-gate.sh` → bloquea pushes hechos
   fuera de Claude.
4. **`scripts/quality-gate.sh`**: `spotless:check` → `checkstyle:check` → `test` → `jacoco:check`,
   con mensajes claros y códigos de salida distintos por etapa.
5. **Skills** en `.claude/skills/<nombre>/SKILL.md` (frontmatter `name`, `description`):
   - `quality-gate`: ejecutar el gate, interpretar fallos y corregirlos (formato, checkstyle,
     cobertura).
   - `new-endpoint`: flujo contract-first para agregar endpoint (editar `openapi.yaml` → DTOs →
     caso de uso → controller → Problem Details → pruebas → gate).
   - `problem-details`: catálogo de errores RFC 9457 del servicio y cómo agregar uno nuevo
     (tipo URI, título, plantilla Commons Text, status).
   - `docker-release`: construir imagen, levantar `compose.yaml`, verificar health y trazas.
6. **Subagente** `.claude/agents/code-security-reviewer.md`:
   - frontmatter: `name: code-security-reviewer`, `description` (usar antes de cada push o PR),
     `tools: Read, Grep, Glob, Bash`, `model: opus`.
   - Checklist: Google Style, SOLID/hexagonal, manejo de dinero con BigDecimal, condiciones de
     carrera, transacciones, inyección SQL (sólo parámetros), validación de entrada, exposición de
     datos sensibles en logs/errores, OWASP API Top 10, Problem Details consistentes, cobertura real
     de ramas de negocio, secretos en código.
   - Salida: tabla de hallazgos (severidad CRITICAL/HIGH/MEDIUM/LOW, archivo:línea, evidencia,
     corrección) y veredicto `APPROVED`/`CHANGES_REQUESTED`. Si `APPROVED`, escribe
     `target/review/REVIEW_OK` con el hash de `HEAD` (el hook de push lo exige).
7. **`.mcp.json`** de §2.
8. Criterio: probar cada hook con un caso que pasa y uno que falla (p. ej. un archivo Java mal
   formateado bloquea el push) y pegar la evidencia en el reporte.

### A3 — Dominio + Persistencia + Concurrencia (Fase 2)

- Dominio puro (sin Spring): `Channel` (enum), `Money`/montos `BigDecimal`, `ChannelLimit`
  (record con invariantes), `LimitDecision` (sealed interface `Approved`/`Rejected` con
  `RejectionReason`), `LimitPolicy` (regla pura de aprobación).
- Puertos: `LimitRepository`, `ConsumptionRepository`, `TransferValidationRepository`.
- Adaptadores JPA + consultas nativas de §6; migraciones Flyway; `BusinessDateProvider` con `Clock`.
- Validar con MCP `postgres` si hay BD local; si no, reportarlo.
- Consultar `context7` para Hibernate 7 / Spring Data 2026.x.

### A4 — API web + RFC 9457 + Commons Text (Fase 2)

- Controllers que implementan exactamente el contrato `openapi.yaml` (rutas, códigos, esquemas).
- Records DTO con Bean Validation; mapper manual.
- Caso de uso `ValidateTransferUseCase`, `GetAccountLimitsUseCase`, `UpdateChannelLimitUseCase`.
- `RejectionMessageFactory` con `StringSubstitutor` y plantillas en
  `src/main/resources/messages/rejections.properties` (montos formateados `es-MX`).
- `GlobalExceptionHandler` (`@RestControllerAdvice` + `ResponseEntityExceptionHandler`) →
  `ProblemDetail` con `type`, `title`, `status`, `detail`, `instance`, `code`, `traceId`, `errors`.
  Casos: validación (400), límite no encontrado (404), rechazo de negocio (422), conflicto de
  versión (409), genérico (500 sin filtrar detalles internos).

### A5 — Observabilidad + Contenedores (Fase 2)

- `application.yml` con perfiles `default` y `docker`; datasource por variables de entorno;
  actuator `health,info,prometheus`; `management.tracing.sampling.probability`;
  `management.opentelemetry.tracing.export.otlp.endpoint`; patrón de log con `traceId`/`spanId`.
- `ObservationConfig` y spans/atributos (`account.id` enmascarado, `channel`, `decision`).
- `Dockerfile` multi-stage, `.dockerignore`, `compose.yaml` (postgres 18, otel-collector o Jaeger
  all-in-one, app con healthcheck y `depends_on: condition: service_healthy`).

### A6 — Pruebas unitarias ≥ 90 % (Fase 2, arranca con el contrato y se re-ejecuta en Fase 3)

- JUnit 5 + Mockito + AssertJ; `@WebMvcTest` por controller (sin BD) validando códigos y cuerpos
  `application/problem+json`.
- Pruebas de `LimitPolicy` parametrizadas (límites exactos, montos 0/negativos, escala).
- Prueba de concurrencia de §6 (≥ 50 hilos, verificar suma aprobada ≤ límite).
- Pruebas de `RejectionMessageFactory`, `GlobalExceptionHandler`, idempotencia, `BusinessDate` con
  `Clock.fixed`.
- Prohibido: Testcontainers, `@SpringBootTest` con datasource real, Failsafe.
- Criterio: `./mvnw verify` con JaCoCo ≥ 90 % líneas y ramas.

### A7 — Subagente revisor `code-security-reviewer` (Fase 3)

Se invoca con el subagente definido por A2 sobre el diff completo. Si devuelve
`CHANGES_REQUESTED`, el orquestador asigna cada hallazgo CRITICAL/HIGH al agente dueño del archivo
y repite (máx. 3 iteraciones). MEDIUM/LOW se corrigen o se documentan en README.

### A8 — Documentación (Fase 4)

`README.md` en español con:
1. Descripción y arquitectura (diagrama Mermaid).
2. Requisitos previos (JDK 27, Docker opcional) y **pasos para ejecutar**: `mvnw verify`,
   `mvnw spring-boot:run` con PostgreSQL, `docker compose up --build`, ejemplos `curl` de los 4
   endpoints, URL de Swagger UI y de Jaeger.
3. **Agente utilizado**: Claude Code con Claude Opus 5.5 como orquestador, enjambre de sub-agentes
   (tabla A1–A8), MCP usados y para qué.
4. **Estrategia paso a paso**: fases 0–4 de este prompt, decisiones (ADRs), cómo se garantizan
   concurrencia, RFC 9457, OTel y cobertura.
5. Harness: tabla de `CLAUDE.md`, settings, skills, subagente, hooks (Claude y git) y cómo
   activarlos (`git config core.hooksPath .githooks`).
6. Exclusiones explícitas (pruebas de integración, escaneo de dependencias) y limitaciones
   conocidas (p. ej. imagen Docker no verificada localmente si no hay Docker).
7. Enlace a este `PROMPT_ENJAMBRE.md`.

---

## 8. Plan de ejecución del orquestador

**Fase 0 — Preparación**
1. Verificar conexión de MCP (`github`, `context7`, `fetch`, `postgres`); reportar los que fallen.
2. Instalar JDK 27 portable en `%USERPROFILE%\.local\devtools\jdk` (nunca dentro del repo) y
   exportar `JAVA_HOME` en la sesión.
3. `git init -b main` en `U:\Edgar\Estudio\Banamex`; `.gitignore` (Maven, IDE, `.env`, `target/`).
4. Con **GitHub MCP**, crear `edgarcdmx/MS-TRANSFER-LIMITS` (confirmar visibilidad con el usuario)
   y configurar `origin`.

**Fase 1** — Lanzar A1; revisar contrato y `pom.xml`; commit `chore: scaffold project and OpenAPI contract`.

**Fase 2** — Lanzar A2, A3, A4, A5, A6 **en un solo mensaje** (paralelo, `isolation: "worktree"`).
Esperar todas las notificaciones; no adivinar resultados pendientes.

**Fase 3** — Integrar ramas de worktree en `main` resolviendo conflictos según la tabla de
propiedad; `./mvnw -B verify`; corregir; lanzar `code-security-reviewer`; bucle hasta `APPROVED`.

**Fase 4** — Lanzar A8; `./mvnw verify` final; commits convencionales por área; `git push -u origin main`
**pasando por los hooks** (nunca `--no-verify`); confirmar con GitHub MCP que el repo contiene el
código; informar al usuario: URL, resultado de pruebas, cobertura, veredicto del revisor y
limitaciones.

---

## 9. Definición de terminado

- [ ] Repo `edgarcdmx/MS-TRANSFER-LIMITS` existe en GitHub con todo el código y artefactos.
- [ ] `./mvnw verify` verde: formato Google, Checkstyle, pruebas, JaCoCo ≥ 90 %.
- [ ] 4 endpoints conformes a `openapi.yaml`; errores `application/problem+json` (RFC 9457).
- [ ] Rechazos con plantillas Commons Text.
- [ ] Prueba de concurrencia demuestra que no se excede el límite diario.
- [ ] Trazas OTel configuradas y `traceId` en logs y errores.
- [ ] `Dockerfile` + `compose.yaml` listos.
- [ ] `CLAUDE.md`, `.claude/settings.json`, ≥ 3 skills, subagente revisor, hooks Claude + git,
      `.mcp.json`.
- [ ] Hook de push probado: bloquea cuando falla el gate o falta la aprobación del revisor.
- [ ] Sin pruebas de integración ni escaneo de dependencias.
- [ ] `README.md` con ejecución, agente utilizado y estrategia paso a paso.
