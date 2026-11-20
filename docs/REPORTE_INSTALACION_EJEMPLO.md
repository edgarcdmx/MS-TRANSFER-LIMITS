# Reporte de instalación — MS-TRANSFER-LIMITS

| Campo | Valor |
|---|---|
| Fecha | 2026-11-19 19:14:38 -0600 |
| Veredicto | **EXITOSA (13 PASS, 1 SKIPPED, 0 FAIL)** |
| Modo | `local` (solicitado `local`) |
| Duración total | 173 s |
| Comando | `./install.sh --mode local --retries 3` |

## Entorno

| Elemento | Valor |
|---|---|
| Sistema operativo | MINGW64_NT-10.0-26200 (windows), arquitectura x86_64 |
| bash | 5.3.9(1)-release |
| git | git version 2.54.0.windows.1 |
| curl | curl 8.19.0 (x86_64-w64-mingw32) libcurl/8.19.0 Schannel zlib/1.3.2 brotli/1.2.0 zstd/1.5.7 libidn2/2.3.8 libpsl/0.21.5 libssh2/1.11.1 WinLDAP |
| tar | tar (GNU tar) 1.35 |
| unzip | UnZip 6.00 of 20 April 2009 |
| python3 (opcional) | python3 |
| Commit | main @ 71976a3 |
| Espacio libre (repo) | 760862 MB |
| Proxy | ninguno |
| Modo | local (solicitado: local) |
| JDK | openjdk version "27.0.1.0.2" 2026-10-21 OpenJDK Runtime Environment Temurin-27.0.1.0.2+1 (build 27.0.1.0.2+1)  |
| Maven | Apache Maven 3.9.16 (Maven Wrapper) |
| Spring Boot | 4.2.0 |
| JVM en ejecución | using Java 27.0.1.0.2 |
| PostgreSQL | 18.7.0 (binarios zonky embedded-postgres desde Maven Central), puerto 5432 |
| URL de la API | http://127.0.0.1:8080 |

## Fases

| Fase | Estado | Duración | Intentos | Notas / auto-reparación |
|---|---|---|---|---|
| 0. Preflight (sistema, herramientas, puertos, proxy) | OK | 6 s | 1 | — |
| 1. JDK 27 | OK | 1 s | 1 | JAVA_HOME existente |
| 2. Build Maven Wrapper + gate de calidad | OK | 69 s | 1 | — |
| 3. Runtime (local) | OK | 41 s | 1 | binarios PostgreSQL reutilizados |
| 4. Demo y verificacion de requisitos R1-R14 | OK | 55 s | 1 | — |

## Requisitos

| ID | Requisito | Estado | Verificaciones | Evidencia |
|---|---|---|---|---|
| R1 | Java 27 + Spring Boot 4.2.0 | **PASS** | 3/3 | Spring Boot 4.2.0; using Java 27.0.1.0.2; build JDK: openjdk version "27.0.1.0.2" 2026-10-21 OpenJDK Runtime Environment Temurin-27.0.1.0.2+1 (build 27.0.1.0.2+1)  |
| R2 | Build Maven con wrapper | **PASS** | 2/2 | Apache Maven 3.9.16 via ./mvnw; verify; log target/install/logs/build-attempt1.log |
| R3 | Google Java Style (Spotless + Checkstyle) | **PASS** | 2/2 | spotless:check + checkstyle:check en verify; You have 0 Checkstyle violations; failOnViolation=true |
| R4 | Errores RFC 9457 (Problem Details) | **PASS** | 7/7 | codigos verificados 400/404/409/422 con application/problem+json y type/title/status/detail/instance/code/traceId |
| R5 | Trazas OpenTelemetry | **PASS** | 3/3 | traceId en Problem Details (7) y en el log (7); OTLP/HTTP exportado a receptor local (7 envios, 7 traceId encontrados). Jaeger no disponible sin Docker |
| R6 | Imagen Docker lista | **SKIPPED** | 0/0 | Docker no disponible en este equipo (modo local): no se construyo la imagen. Verificacion estatica: Dockerfile (eclipse-temurin:27, HEALTHCHECK, USER spring) y compose.yaml presentes |
| R7 | Cobertura JaCoCo >= 90 % | **PASS** | 2/2 | LINE 100.00 % (592/592), BRANCH 100.00 % (116/116); jacoco:check en verify; Tests run: 267, Failures: 0, Errors: 0, Skipped: 0 |
| R8 | Concurrencia (limite diario atomico + idempotencia) | **PASS** | 6/6 | 40 peticiones paralelas en 2971 ms: 201=33, 422=7, otros=0; consumedToday=9900.00 (limite 10000.00, esperado 9900.00); 20 peticiones paralelas mismo transferId: 201=1, 200=19, otros=0; consumedToday=300.00 (esperado 300.00) |
| R9 | Contrato OpenAPI 3 publicado | **PASS** | 3/3 | /openapi/openapi.yaml (openapi: 3.1.0), /v3/api-docs 200, swagger-ui 200 |
| R10 | PostgreSQL + Flyway (persistencia) | **PASS** | 4/4 | PostgreSQL 18.7.0 embebido (zonky) en 127.0.0.1:5432; Successfully applied 1 migration to schema "public"; datos y replay sobreviven al reinicio de la app |
| R11 | Reglas de negocio (PUT/GET/POST, 201/200/409/400/404/422) | **PASS** | 22/22 | PUT x3 200; POST 201+Location; replay 200 identico; 422 por operacion/diario; 409; 400 errors[]; 404; consumo final 41,500.00 |
| R12 | Mensajes con Apache Commons Text | **PASS** | 2/2 | 422 por operacion: "El monto 25,000.00 MXN excede el límite por operación de 20,000.00 MXN para el canal MOBILE_APP."; 422 diario: "El monto 15,000.00 MXN excede el disponible diario de 8,500.00 MXN (límite 50,000.00, consumido 41,500.00) para el canal MOBILE_APP." |
| R13 | Harness Claude Code + bloqueo de push | **PASS** | 6/6 | CLAUDE.md, settings.json, 4 skills, subagente, 5 hooks; test-hooks: hook tests: 52 passed, 0 failed; core.hooksPath=.githooks |
| R14 | Health /actuator/health UP | **PASS** | 1/1 | HTTP 200 {"groups":["liveness","readiness"],"status":"UP"} |

## Concurrencia (R8)

- Límite diario 10,000.00 MXN, monto 300.00 MXN: 40 peticiones paralelas en 2971 ms: 201=33, 422=7, otros=0; consumedToday=9900.00 (limite 10000.00, esperado 9900.00)
- Idempotencia concurrente: 20 peticiones paralelas mismo transferId: 201=1, 200=19, otros=0; consumedToday=300.00 (esperado 300.00)

## Cobertura (R7)

- Líneas: **100.00 %** · Ramas: **100.00 %** (umbral 90 %, `jacoco:check` en `verify`)
- Pruebas: Tests run: 267, Failures: 0, Errors: 0, Skipped: 0
- Reporte HTML: `target/site/jacoco/index.html`

## Verificaciones detalladas

```text
PASS [R14] GET /actuator/health -> 200, status UP
PASS [R1] Spring-Boot-Version del jar = 4.2.0 (esperado 4.2.0)
PASS [R1] JVM 27 (using Java 27.0.1.0.2)
PASS [R1] pom.xml java.version=27
PASS [R2] mvnw + .mvn/wrapper/maven-wrapper.properties presentes
PASS [R2] ./mvnw -B verify -> BUILD SUCCESS (Apache Maven 3.9.16)
PASS [R3] spotless:check ejecutado en verify sin violaciones
PASS [R3] checkstyle:check (google_checks.xml) ejecutado sin violaciones
PASS [R11] PUT limite MOBILE_APP (50,000 diario / 20,000 por operacion) -> 200
PASS [R11] PUT limite WEB -> 200
PASS [R11] PUT limite BRANCH -> 200
PASS [R11] GET limites -> 200 con 3 canales
PASS [R11] POST 1,500.00 -> 201 APPROVED con Location: /api/v1/accounts/DEMO-19163697/transfer-validations/05abae74-0001-4001-8001-000000000001
PASS [R11] reintento idempotente mismo transferId -> 200 con cuerpo identico
PASS [R11] POST 25,000.00 > limite por operacion -> HTTP 422 (esperado 422)
PASS [R4] POST 25,000.00 > limite por operacion: application/problem+json con type/title/status/detail/instance/code/traceId
PASS [R11] detail en espanol: 'excede el límite por operación'
PASS [R11] code=PER_OPERATION_LIMIT_EXCEEDED
PASS [R12] plantilla Commons Text con montos es-MX: "El monto 25,000.00 MXN excede el límite por operación de 20,000.00 MXN para el canal MOBILE_APP."
PASS [R11] POST 20,000.00 (acumulado 21,500.00) -> 201
PASS [R11] POST 20,000.00 (acumulado 41,500.00) -> 201 consumedToday=41500.00
PASS [R11] POST 15,000.00 > disponible diario 8,500.00 -> HTTP 422 (esperado 422)
PASS [R4] POST 15,000.00 > disponible diario 8,500.00: application/problem+json con type/title/status/detail/instance/code/traceId
PASS [R11] detail en espanol: 'excede el disponible diario'
PASS [R11] code=DAILY_LIMIT_EXCEEDED
PASS [R12] plantilla diaria con disponible/limite/consumido formateados: "El monto 15,000.00 MXN excede el disponible diario de 8,500.00 MXN (límite 50,000.00, consumido 41,500.00) para el canal MOBILE_APP."
PASS [R11] mismo transferId con otro monto (conflicto) -> HTTP 409 (esperado 409)
PASS [R4] mismo transferId con otro monto (conflicto): application/problem+json con type/title/status/detail/instance/code/traceId
PASS [R11] code=TRANSFER_ID_CONFLICT
PASS [R11] solicitud invalida (sin channel, monto negativo) -> HTTP 400 (esperado 400)
PASS [R4] solicitud invalida (sin channel, monto negativo): application/problem+json con type/title/status/detail/instance/code/traceId
PASS [R11] 400 incluye errors[] (2 campos)
PASS [R11] GET limites de cuenta desconocida -> HTTP 404 (esperado 404)
PASS [R4] GET limites de cuenta desconocida: application/problem+json con type/title/status/detail/instance/code/traceId
PASS [R11] POST a cuenta sin limites -> HTTP 404 (esperado 404)
PASS [R4] POST a cuenta sin limites: application/problem+json con type/title/status/detail/instance/code/traceId
PASS [R11] PUT con limite por operacion > diario -> HTTP 422 (esperado 422)
PASS [R4] PUT con limite por operacion > diario: application/problem+json con type/title/status/detail/instance/code/traceId
PASS [R11] GET final: MOBILE_APP consumedToday=41500.00 (rechazos y reintentos no consumen)
PASS [R8] consumedToday=9900.00 <= limite diario 10000.00
PASS [R8] aprobadas=33 == floor(10000/300)=33
PASS [R8] consumedToday == aprobadas x 300.00 (9900)
PASS [R8] sin respuestas distintas de 201/422 (otros=0)
PASS [R8] mismo transferId x20: un 201 y 19 replays 200 (201=1, 200=19, otros=0)
PASS [R8] consumido una sola vez: consumedToday=300.00
PASS [R9] GET /openapi/openapi.yaml -> 200 (openapi: 3.1.0)
PASS [R9] GET /v3/api-docs -> 200
PASS [R9] Swagger UI /swagger-ui.html -> 200
PASS [R13] CLAUDE.md presente
PASS [R13] .claude/settings.json con hooks PreToolUse
PASS [R13] skills: 4 (docker-release new-endpoint problem-details quality-gate )
PASS [R13] subagente .claude/agents/code-security-reviewer.md
PASS [R13] scripts de hooks presentes (5/5) + .githooks/pre-push
PASS [R13] bash scripts/hooks/test-hooks.sh -> hook tests: 52 passed, 0 failed (exit 0)
PASS [R7] cobertura de lineas 100.00 % >= 90 %
PASS [R7] cobertura de ramas 100.00 % >= 90 %
PASS [R10] Flyway: Successfully applied 1 migration to schema "public"
PASS [R10] health UP con datasource activo: PostgreSQL 18.7.0 embebido (zonky) en 127.0.0.1:5432
PASS [R10] tras reiniciar la app los datos persisten (MOBILE_APP consumedToday=41500.00)
PASS [R10] idempotencia persistida: replay del primer transferId tras reinicio -> 200
PASS [R5] traceId W3C (32 hex) en 7 Problem Details
PASS [R5] traceId de las respuestas presente en lineas del log de la app (7/7)
PASS [R5] exportador OTLP/HTTP: 7 envios a /v1/traces; 7/7 traceId de Problem Details presentes en los spans exportados
```

## Logs

| Log | Ruta |
|---|---|
| Consola del instalador | `target/install/logs/install.log` |
| Build Maven | `target/install/logs/build-attempt1.log` |
| test-hooks.sh | `target/install/logs/test-hooks.log` |
| Aplicación | `target/install/logs/app.log` (reinicio: `target/install/logs/app-restart.log`) |
| PostgreSQL | `target/install/logs/postgres.log` |

El entorno de ejecución se detuvo al terminar (use `--keep-running` para dejarlo arriba).
