---
name: new-endpoint
description: Contract-first workflow to add or change a REST endpoint in MS-TRANSFER-LIMITS (openapi.yaml -> DTOs -> use case -> controller -> Problem Details -> tests -> gate). Use whenever the user asks to add, modify or remove an API operation, path, parameter, request/response field or status code.
---

# New endpoint (contract-first)

The OpenAPI 3.1 contract `src/main/resources/openapi/openapi.yaml` is the source of truth
(ADR-003). Code follows the contract, never the other way round.

## Steps

1. **Contract** — edit `openapi.yaml` first:
   - path under `/api/v1/...`, `operationId` (camelCase verb), tags, path params (`accountId`,
     `channelId` — check spelling), request body `$ref` to a schema in `components/schemas`;
   - every response: success code (`200`/`201`) and each error as `application/problem+json`
     referencing the `ProblemDetail` schema with an example per error code
     (see the `problem-details` skill);
   - money fields: `type: string` or `number` exactly as existing schemas do, with `multipleOf: 0.01`
     / pattern, `exclusiveMinimum: 0` for amounts; `currency` enum `MXN`.
   - If in doubt about OpenAPI 3.1 / springdoc 3 behaviour, query the `context7` MCP.
2. **DTOs** (`api/dto`): Java `record`s mirroring the schemas, with Bean Validation
   (`@NotNull`, `@Positive`, `@Digits(integer = 17, fraction = 2)`, `@Pattern`). Money is
   `BigDecimal`. Add Javadoc on public records.
3. **Domain** (`domain`): new rules go into pure domain classes (no Spring). New outbound needs ->
   new port interface in `domain/port`, implemented in `infrastructure/persistence` (parameterized
   SQL only; new tables/columns -> new Flyway `V<n+1>__description.sql`).
4. **Use case** (`application`): one class per use case, `@Transactional` where it writes, takes the
   `Clock`/`BusinessDateProvider` for time, returns domain results (no HTTP types).
5. **Controller** (`api`): thin; maps DTO <-> domain with a manual mapper, returns
   `ResponseEntity` with the exact status from the contract (`201` + `Location` for creations).
6. **Errors**: map new failure modes to domain exceptions handled by the global
   `@RestControllerAdvice` -> `ProblemDetail` (type URI, title, status, `code`, `traceId`,
   `errors[]`). New codes: follow the `problem-details` skill. Rejection texts go to
   `messages/rejections.properties` (Commons Text `${placeholders}`).
7. **Observability**: add a span/attributes only for meaningful business steps; mask `accountId`.
8. **Tests** (unit only):
   - `@WebMvcTest(Controller.class)` with mocked use cases: happy path body/status, each 4xx with
     `Content-Type: application/problem+json` and `$.code`, `$.type`, `$.errors`;
   - use case tests with Mockito + `Clock.fixed`; domain tests parameterized on boundaries;
   - keep the contract-vs-endpoints test green (it compares `openapi.yaml` paths/methods/statuses
     with the controllers).
9. **Gate**: run the `quality-gate` skill; then commit; then `code-security-reviewer`.

## Checklist

- [ ] `openapi.yaml` updated first, examples included, `accountId` spelled correctly
- [ ] DTO validation matches schema constraints
- [ ] No Spring/JPA imports in `domain`
- [ ] Errors are RFC 9457 with a catalogued `code`
- [ ] `BigDecimal` scale 2, `Clock` for time
- [ ] `@WebMvcTest` + unit tests cover every branch; gate green
