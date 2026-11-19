---
name: problem-details
description: Catalogue of MS-TRANSFER-LIMITS RFC 9457 error responses (codes, HTTP status, type URI, title) and the procedure to add a new error code. Use when writing or reviewing error handling, the @RestControllerAdvice, rejection messages, openapi.yaml error responses, or tests that assert application/problem+json bodies.
---

# Problem Details (RFC 9457)

Every error response is `Content-Type: application/problem+json`:

```json
{
  "type": "https://api.banamex.example/problems/daily-limit-exceeded",
  "title": "Límite diario excedido",
  "status": 422,
  "detail": "El monto 15,000.00 MXN excede el disponible diario de 8,500.00 MXN (límite 50,000.00, consumido 41,500.00) para el canal MOBILE_APP.",
  "instance": "/api/v1/accounts/0012345678/transfer-validations",
  "code": "DAILY_LIMIT_EXCEEDED",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "transferId": "3f1c2b9e-7a4d-4c1e-9b8a-2d6f5e4c3b2a",
  "reasonCode": "DAILY_LIMIT_EXCEEDED"
}
```

Rules: `type` = `https://api.banamex.example/problems/<kebab-case of code>`; `code` = the
UPPER_SNAKE constant below; `traceId` = current OTel trace id (never null when tracing is on);
`errors[]` = field errors `{ "field", "message" }` (schema `FieldError`, only for
`VALIDATION_ERROR`; never echo rejected values); `instance` = request path. Transfer rejections and
`TRANSFER_ID_CONFLICT` also carry `transferId`; rejections carry `reasonCode` (= `code`). Titles and
`detail` texts are in Spanish, exactly as in `components/examples` of `openapi.yaml`. 5xx never expose exception messages, class
names, SQL or stack traces.

## Catalogue

| code | status | type suffix | title (es) | When |
|---|---|---|---|---|
| `VALIDATION_ERROR` | 400 | `validation-error` | Solicitud inválida | Bean Validation failure, malformed JSON, bad UUID/enum/path param, amount <= 0 or scale > 2. `errors[]` filled. |
| `ACCOUNT_LIMITS_NOT_FOUND` | 404 | `account-limits-not-found` | Límites no encontrados | GET limits for an account with no configured channels. |
| `LIMIT_NOT_FOUND` | 404 | `limit-not-found` | Límite no encontrado | Transfer validation for an account/channel without a configured limit. |
| `TRANSFER_ID_CONFLICT` | 409 | `transfer-id-conflict` | Conflicto de transferId | Same `transferId` replayed with a different account/channel/amount (an identical replay returns the original result with 200). |
| `INVALID_LIMIT_CONFIGURATION` | 422 | `invalid-limit-configuration` | Configuración de límite inválida | PUT limit with `perOperationLimit > dailyLimit` (or other cross-field rule). |
| `PER_OPERATION_LIMIT_EXCEEDED` | 422 | `per-operation-limit-exceeded` | Límite por operación excedido | `amount > perOperationLimit`; recorded as REJECTED, no consumption. Message template in `rejections.properties`. |
| `DAILY_LIMIT_EXCEEDED` | 422 | `daily-limit-exceeded` | Límite diario excedido | Atomic conditional UPDATE affected 0 rows (`consumed + amount > dailyLimit`). Message template in `rejections.properties`. |
| `INTERNAL_ERROR` | 500 | `internal-error` | Error interno | Any unexpected exception. Generic `detail`, still with `traceId`. Log the exception server-side. |

Business rejections (`*_LIMIT_EXCEEDED`) build `detail` with Apache Commons Text
`StringSubstitutor` from `src/main/resources/messages/rejections.properties`, e.g.
`DAILY_LIMIT_EXCEEDED=El monto ${amount} ${currency} excede el disponible diario de ${available} ${currency} (límite ${dailyLimit}, consumido ${consumed}) para el canal ${channel}.`
Amounts are formatted with locale `es-MX`, scale 2.

## Adding a new error code

1. Pick an UPPER_SNAKE `code` and its kebab `type` suffix; choose the status by semantics
   (400 syntax/validation, 404 missing resource, 409 state conflict, 422 business rule, 5xx server).
2. Add it to the error-code enum/catalogue class in `api/error` (code, status, title, type URI) —
   one place only; the advice derives `type` from the code.
3. Create a domain/application exception (no HTTP types in `domain`) carrying the data needed for
   `detail`; map it in the global `@RestControllerAdvice` (extends `ResponseEntityExceptionHandler`).
4. If the `detail` is user-facing text with variables, add a template to
   `messages/rejections.properties` (`${var}` placeholders, no concatenation).
5. Document it in `openapi.yaml`: response under every affected operation with
   `application/problem+json`, `$ref: '#/components/schemas/ProblemDetail'` and an example.
6. Add the row to the catalogue table above (this file) and to the README if public.
7. Tests: `@WebMvcTest` asserting status, content type, `$.type`, `$.title`, `$.code`, `$.traceId`
   presence and `$.detail`; unit test of the message factory for the template.
8. Run the `quality-gate` skill.
