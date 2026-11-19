# ADR-003 — Contrato OpenAPI primero (contract-first)

- **Estado:** Aceptado
- **Fecha:** 2026-11-19

## Contexto

Varios agentes construyen en paralelo la API web, la persistencia y las pruebas. Necesitan una
única fuente de verdad para rutas, códigos HTTP, esquemas y errores antes de escribir código (R9).

## Decisión

- El contrato vive en `src/main/resources/openapi/openapi.yaml` (OpenAPI **3.1**) y se escribe
  **antes** que el código. Todo cambio de API empieza editando este archivo (skill `new-endpoint`).
- Identificadores en inglés; descripciones en español.
- Errores modelados con el esquema `ProblemDetail` (RFC 9457, `application/problem+json`) con
  extensiones `code`, `traceId`, `errors[]`, `transferId`, `reasonCode`; `type` =
  `https://api.banamex.example/problems/<kebab-code>`.
- Cada respuesta tiene al menos un ejemplo.
- No se genera código desde el contrato (sin `openapi-generator`): los DTOs se escriben a mano como
  `record` con Bean Validation, para mantener control sobre Google Java Style y la cobertura.
- springdoc expone la API en tiempo de ejecución (`/v3/api-docs`, Swagger UI). Una prueba
  `@WebMvcTest` compara rutas y métodos del contrato con los endpoints registrados, de modo que una
  divergencia rompe `./mvnw verify`.
- El contrato se valida con `swagger-parser` (sin mensajes de error).

## Consecuencias

- (+) Los agentes de API, pruebas y documentación trabajan en paralelo contra el mismo contrato.
- (+) Los consumidores conocen los errores (`code`, `reasonCode`) sin leer código.
- (−) Al no generar código, la consistencia depende de la prueba de conformidad y de la revisión.
- (−) Doble mantenimiento contrato ↔ DTOs; se mitiga con la skill `new-endpoint` y el gate.
