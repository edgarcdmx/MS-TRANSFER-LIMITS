# ADR-002 — Control de concurrencia del consumo diario

- **Estado:** Aceptado
- **Fecha:** 2026-11-19

## Contexto

Dos validaciones simultáneas para la misma cuenta y canal **nunca** deben dejar el consumo del día
por encima del límite diario (regla 7, R8). El patrón ingenuo "leer consumido → comparar en Java →
escribir" tiene una condición de carrera *lost update*: ambas transacciones leen el mismo valor,
ambas aprueban y el total excede el límite.

## Opciones consideradas

| Opción | Cómo funciona | Pros | Contras |
|---|---|---|---|
| **A. `UPDATE` atómico condicionado** (elegida) | La condición de límite va dentro del `WHERE` del `UPDATE` | Una sola sentencia; bloqueo de fila mínimo; sin reintentos; correcto en READ COMMITTED | La regla del límite diario queda también expresada en SQL |
| B. `SELECT … FOR UPDATE` | Bloqueo pesimista de la fila, decisión en Java, luego `UPDATE` | Lógica en Java, fácil de leer | Dos viajes a BD con el lock retenido; más contención y latencia; riesgo de deadlocks si el orden de bloqueo varía; la fila debe existir antes de bloquearla |
| C. Bloqueo optimista (`@Version`) | Leer, calcular, `UPDATE … WHERE version = :v`; si 0 filas, reintentar | Sin locks explícitos | Con contención (ráfagas sobre la misma cuenta) produce muchos conflictos y reintentos; exige política de reintentos y backoff; peor latencia p99 |

## Decisión

Opción A, dentro de una transacción `@Transactional` en READ COMMITTED:

1. Leer el límite del canal; si no existe → 404 `LIMIT_NOT_FOUND`.
2. Si `amount > per_operation_limit` → rechazo `PER_OPERATION_LIMIT_EXCEEDED` (se registra, no consume).
3. Garantizar la fila del día:
   ```sql
   INSERT INTO daily_consumption (account_id, channel, business_date, consumed)
   VALUES (:acc, :ch, :date, 0) ON CONFLICT DO NOTHING;
   ```
4. Consumo atómico condicionado:
   ```sql
   UPDATE daily_consumption
      SET consumed = consumed + :amount
    WHERE account_id = :acc AND channel = :ch AND business_date = :date
      AND consumed + :amount <= :dailyLimit
   RETURNING consumed;
   ```
   0 filas → rechazo `DAILY_LIMIT_EXCEEDED`.
5. Insertar `transfer_validation`; violación de PK por `transfer_id` → devolver el resultado
   existente (idempotencia) o 409 `TRANSFER_ID_CONFLICT` si el payload difiere.

**Por qué es correcto:** PostgreSQL toma un bloqueo de fila al ejecutar el `UPDATE`. Si otra
transacción ya modificó la fila, la segunda espera su commit y, en READ COMMITTED, **re-evalúa el
`WHERE` sobre la versión comprometida** (EvalPlanQual). Si el nuevo `consumed + amount` excede el
límite, la fila deja de cumplir la condición y se devuelven 0 filas. No se necesita SERIALIZABLE ni
reintentos. El `CHECK (consumed >= 0)` y `NUMERIC(19,2)` protegen el resto de invariantes.

## Verificación

Sin BD real (R11): la prueba unitaria usa un adaptador en memoria del puerto
`ConsumptionRepository` que reproduce la semántica del `UPDATE` condicionado con
`ConcurrentHashMap.compute` (atómico por clave) y lanza ≥ 50 hilos (`ExecutorService` +
`CountDownLatch`), verificando que la suma aprobada ≤ límite diario y que aprobados + rechazados = N.
Si hay PostgreSQL local, el agente de persistencia valida las sentencias con el MCP `postgres`.

## Consecuencias

- (+) Correcto bajo concurrencia con una sola sentencia y sin reintentos.
- (+) El bloqueo dura sólo el resto de una transacción corta (insertar `transfer_validation`).
- (−) La comparación contra el límite diario existe en SQL y en `LimitPolicy` (para el mensaje de
  rechazo); la autoridad final es la sentencia SQL.
- (−) Depende de la semántica de PostgreSQL (`ON CONFLICT`, `RETURNING`); aceptable porque la BD es
  un requisito (R10).
