---
name: docker-release
description: Build the MS-TRANSFER-LIMITS Docker image, start the stack with docker compose (PostgreSQL, app, OpenTelemetry/Jaeger), verify /actuator/health and see traces in Jaeger. Use when the user asks to build/run the container, release an image, smoke-test the service end to end, or check traces.
---

# Docker release and smoke test

Files: `Dockerfile` (multi-stage: `eclipse-temurin:27-jdk` build -> `eclipse-temurin:27-jre`
runtime, Spring Boot layers via `-Djarmode=tools`, non-root user, `HEALTHCHECK`), `compose.yaml`
(postgres 18, app, Jaeger all-in-one / OTel collector), `.dockerignore`.

Docker may not be installed on the development machine; if `docker version` fails, say so and stop
(do not claim the image was verified).

## Steps

1. Gate first: run the `quality-gate` skill (the image must come from green code).
2. Build:
   ```bash
   docker build -t ms-transfer-limits:local .
   docker image inspect ms-transfer-limits:local --format '{{.Config.User}}'   # must not be root/empty
   ```
3. Start the stack:
   ```bash
   docker compose up --build -d
   docker compose ps          # all services healthy (app depends_on postgres: service_healthy)
   docker compose logs -f app # look for "Started" and Flyway "Successfully applied"
   ```
4. Health:
   ```bash
   curl -s http://localhost:8080/actuator/health          # {"status":"UP", ... "db":{"status":"UP"}}
   ```
5. Smoke test the API (generate a fresh UUID for `transferId`):
   ```bash
   curl -s -X PUT http://localhost:8080/api/v1/accounts/0123456789/limits/WEB \
     -H 'Content-Type: application/json' -d '{"dailyLimit":10000.00,"perOperationLimit":5000.00}'
   curl -s -X POST http://localhost:8080/api/v1/accounts/0123456789/transfer-validations \
     -H 'Content-Type: application/json' \
     -d '{"transferId":"3f6c2a52-6d2e-4a8e-9a57-1f5d2b8f0c11","channel":"WEB","amount":1500.00,"currency":"MXN"}'
   curl -s http://localhost:8080/api/v1/accounts/0123456789/limits
   curl -s -i -X POST http://localhost:8080/api/v1/accounts/0123456789/transfer-validations \
     -H 'Content-Type: application/json' \
     -d '{"transferId":"9a1b7a6e-0d55-4c7f-8a43-2b0f6f1e5d22","channel":"WEB","amount":9000.00,"currency":"MXN"}'
   # -> 422 application/problem+json with code PER_OPERATION_LIMIT_EXCEEDED and a traceId
   ```
   Swagger UI: http://localhost:8080/swagger-ui.html · contract: http://localhost:8080/v3/api-docs
6. Traces: open Jaeger at **http://localhost:16686**, service `ms-transfer-limits`, find the
   `transfer-validations` request; its trace id must equal the `traceId` in the Problem Detail and in
   the app log line. OTLP endpoint comes from `OTEL_EXPORTER_OTLP_ENDPOINT`.
7. Tear down: `docker compose down -v` (removes the DB volume).

## Rules

- Never bake secrets into the image or `compose.yaml`; use env vars / `.env` (gitignored, and the
  agent must not read or write it).
- Do not add image/dependency vulnerability scanners to the build (out of scope, R12).
- Report exactly what was verified (build, health, traces) and what could not be (e.g. no Docker).
