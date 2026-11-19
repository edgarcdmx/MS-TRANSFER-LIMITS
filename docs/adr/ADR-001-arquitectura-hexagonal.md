# ADR-001 — Arquitectura hexagonal ligera

- **Estado:** Aceptado
- **Fecha:** 2026-11-19

## Contexto

MS-TRANSFER-LIMITS concentra una regla de negocio crítica (aprobar o rechazar una transferencia
según límites diarios y por operación) que debe probarse de forma aislada, con cobertura ≥ 90 % y
sin pruebas de integración contra base de datos (R7, R11). La lógica no debe depender de Spring, JPA
ni del formato HTTP.

## Decisión

Paquete base `com.banamex.transferlimits`, organizado en capas con dependencias hacia el dominio:

```
api  ──►  application  ──►  domain  ◄──  infrastructure
```

| Capa | Contenido | Depende de |
|---|---|---|
| `domain` | `Channel`, `ChannelLimit`, `LimitDecision` (sealed `Approved`/`Rejected`), `RejectionReason`, `LimitPolicy`, puertos (`LimitRepository`, `ConsumptionRepository`, `TransferValidationRepository`) | Sólo JDK |
| `application` | Casos de uso (`ValidateTransferUseCase`, `GetAccountLimitsUseCase`, `UpdateChannelLimitUseCase`), transacciones, spans | `domain` |
| `api` | Controllers, DTOs (records + Bean Validation), mappers, `GlobalExceptionHandler` (RFC 9457) | `application`, `domain` |
| `infrastructure` | Adaptadores JPA/SQL nativo, Flyway, `BusinessDateProvider` (`Clock`) | `domain` |
| `config` | Wiring de Spring, OpenTelemetry/Observation | todas |

Reglas:

- El dominio no importa `org.springframework`, `jakarta.persistence` ni Jackson.
- Los puertos se definen en `domain` y se implementan en `infrastructure` (inversión de dependencias).
- Dinero siempre en `BigDecimal` escala 2; tiempo siempre vía `Clock` inyectado.
- Los DTOs de `api` nunca llegan al dominio; los mappers son manuales y explícitos.

## Consecuencias

- (+) `LimitPolicy` y los casos de uso se prueban con JUnit/Mockito sin contexto Spring; la
  concurrencia se prueba con un adaptador en memoria (ver ADR-002).
- (+) Cambiar el almacenamiento o el transporte no toca la regla de negocio.
- (+) La propiedad de archivos por agente se alinea con las capas (trabajo paralelo sin conflictos).
- (−) Más clases y mapeos que un CRUD en capas clásico; se acepta por el valor de aislar la regla.
- Es "ligera": no hay módulos Maven separados ni ArchUnit; la disciplina se verifica en revisión
  (subagente `code-security-reviewer`).
