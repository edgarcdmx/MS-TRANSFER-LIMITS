# MS-TRANSFER-LIMITS • Banamex

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3%20%2F%204.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21%20%2F%2027-orange.svg)](https://openjdk.org/)
[![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL%2016-blue.svg)](https://www.postgresql.org/)
[![OpenAPI 3.0](https://img.shields.io/badge/API%20Spec-OpenAPI%203.0-blueviolet.svg)](src/main/resources/openapi/openapi.yaml)
[![Code Style](https://img.shields.io/badge/Code%20Style-Google%20Java-yellow.svg)](https://google.github.io/styleguide/javaguide.html)
[![Test Coverage](https://img.shields.io/badge/JaCoCo-90%25%2B-success.svg)](pom.xml)
[![Live Demo](https://img.shields.io/badge/Firebase%20Demo-Online-red.svg)](https://edgar-ia-expert.web.app/Banamex_Caso_Practico.html)

Microservicio financiero empresarial de alto rendimiento para **Banamex**, diseñado para validar, autorizar y registrar en tiempo real el consumo de límites de transferencia por cliente y canal, garantizando consistencia transaccional absoluta y protección contra sobregiros y condiciones de carrera (*race conditions*).

> 🌐 **Demo Interactiva y Documentación Ejecutiva en Vivo:**  
> Puedes probar la simulación completa de negocio y consultar el dossier de arquitectura y nivel directivo aquí:  
> 👉 **[https://edgar-ia-expert.web.app/Banamex_Caso_Practico.html](https://edgar-ia-expert.web.app/Banamex_Caso_Practico.html)**  
> **Banco:** Banamex | **Cliente Titular:** Edgar Rosas Samaro | **Cuenta:** `8840-3101`

---

## 📋 Tabla de Contenido
1. [Descripción del Negocio](#-descripción-del-negocio)
2. [Arquitectura y Stack Tecnológico](#-arquitectura-y-stack-tecnológico)
3. [Agente Utilizado y Estrategia de Ejecución](#-agente-utilizado-y-estrategia-de-ejecución)
4. [Harness del Agente: Skills, Subagente y Git Hooks](#-harness-del-agente-skills-subagente-y-git-hooks)
5. [Manejo de Concurrencia y Aislamiento](#-manejo-de-concurrencia-y-aislamiento)
6. [Motor de Mensajes Dinámicos (Apache Commons Text)](#-motor-de-mensajes-dinámicos-apache-commons-text)
7. [Errores Estándar RFC 9457 (Problem Details)](#-errores-estándar-rfc-9457-problem-details)
8. [Trazabilidad Distribuida (OpenTelemetry)](#-trazabilidad-distribuida-opentelemetry)
9. [Endpoints Mínimos del Contrato OpenAPI 3.0](#-endpoints-mínimos-del-contrato-openapi-30)
10. [Pasos para Ejecutar el Proyecto](#-pasos-para-ejecutar-el-proyecto)
11. [Contenedorización Docker](#-contenedorización-docker)
12. [Cobertura de Pruebas (JaCoCo 90%)](#-cobertura-de-pruebas-jacoco-90)

---

## 🏦 Descripción del Negocio

El microservicio evalúa si una transferencia bancaria puede efectuarse evaluando las siguientes reglas de negocio:
* **Límites por Canal Transaccional:** Cada cuenta bancaria posee configuraciones independientes de:
  * **Límite por Operación:** Monto máximo autorizado para una sola transacción en el canal.
  * **Límite Diario Acumulado:** Monto máximo total que la cuenta puede transferir durante el día natural.
* **Canales Soportados:**
  * `APP_MOVIL` (App Móvil): Perfil ágil con topes moderados para mitigar riesgo por pérdida/robo del dispositivo.
  * `WEB` (Banca por Internet): Perfil intermedio para pagos cotidianos y servicios.
  * `SUCURSAL` (Ventanilla / Sucursal): Perfil alto con validación presencial para montos elevados.
* **Criterio de Aprobación:**
  $$\text{Aprobada} \iff (\text{Monto} \le \text{Límite por Operación}) \land (\text{Consumo Acumulado Hoy} + \text{Monto} \le \text{Límite Diario})$$
* **Registro Atómico:** Al aprobarse, el monto se suma inmediatamente al consumo acumulado del día de la cuenta para ese canal.

---

## 🛠 Arquitectura y Stack Tecnológico

El microservicio sigue una **Arquitectura Hexagonal (Ports & Adapters)** estricta bajo el paquete base `com.banamex.transferlimits`:
* **Lenguaje y Framework:** Java 21 LTS / Java 27 · Spring Boot 3.3.x / 4.x.
* **Gestor de Compilación:** Apache Maven Wrapper (`./mvnw`).
* **Persistencia:** PostgreSQL 16 + Spring Data JPA + Migraciones Flyway.
* **Formateo de Código:** Google Java Style (`google-java-format 2.0.0` forzado por Spotless y Checkstyle).
* **Contrato API:** Contract-First con especificación OpenAPI 3.0 / 3.1 (`src/main/resources/openapi/openapi.yaml`).
* **Monitoreo y Observabilidad:** Spring Boot Actuator (`/actuator/health`) + OpenTelemetry SDK (W3C Trace Context).

---

## 🤖 Agente Utilizado y Estrategia de Ejecución

### Agente Utilizado
Se utilizó **Claude Code / Antigravity Agent Swarm**, orquestado bajo un marco de ingeniería guiado por contratos, directrices formales (`CLAUDE.md`) y perfiles de automatización delegados.

### Estrategia Paso a Paso Utilizada
1. **Fase de Alineación y Contract-First:**  
   Se diseñó primero la especificación formal OpenAPI 3.0 definiendo esquemas, rutas y respuestas conformes a RFC 9457 antes de escribir código Java.
2. **Construcción del Núcleo de Dominio (Hexagonal):**  
   Se aisló la lógica de límites en entidades y políticas de dominio puro sin dependencias de Spring ni JPA, facilitando pruebas unitarias ultrarrápidas en memoria.
3. **Mecanismo de Concurrencia y Persistencia:**  
   Se modeló el repositorio JPA con `@Lock(LockModeType.PESSIMISTIC_WRITE)` para emitir `SELECT ... FOR UPDATE`, eliminando cualquier posibilidad de condición de carrera entre dos transacciones concurrentes.
4. **Implementación de Plantillas Dinámicas y RFC 9457:**  
   Se integró Apache Commons Text con `StringSubstitutor` para resolver plantillas de rechazo con variables contextuales (monto, diferencia, disponible, canal).
5. **Optimización de Tokens y Eficiencia:**  
   Conforme a la instrucción del caso práctico, **se omitieron las pruebas de integración pesadas (Testcontainers/Docker) y se desactivó el escaneo innecesario de dependencias**, concentrando todos los recursos en una suite exhaustiva de pruebas unitarias con JUnit 5 y Mockito para superar el **90% de cobertura**.
6. **Despliegue del Harness:**  
   Se crearon las skills personalizadas, el subagente de seguridad y los hooks de git bloqueantes.
7. **Simulador Web y Despliegue en Firebase:**  
   Se publicó una demo interactiva completa y un dossier documental en `https://edgar-ia-expert.web.app/Banamex_Caso_Practico.html`.

---

## 🛡 Harness del Agente: Skills, Subagente y Git Hooks

El repositorio incorpora un arnés (*harness*) de protección contra errores, vulnerabilidades y regresiones:

### 1. `CLAUDE.md` y `.claude/settings.json`
Establecen las directrices obligatorias para cualquier modelo o agente: estilo de codificación Google Java Style, política de ramas, comandos estándar y reglas de arquitectura.

### 2. Skills Personalizadas (`.claude/skills/`)
* **`quality-gate`:** Ejecuta y verifica en fases el formateo Spotless, Checkstyle, compilación y JaCoCo.
* **`problem-details`:** Genera y valida respuestas RFC 9457 consistentes en los controladores.
* **`new-endpoint`:** Scaffold guiado para nuevos endpoints respetando la arquitectura hexagonal.
* **`docker-release`:** Construcción y validación del artefacto contenedor.

### 3. Subagente Revisor de Calidad y Seguridad (`.claude/agents/code-security-reviewer.md`)
Subagente autónomo especializado que audita el código fuente buscando:
* Inyecciones SQL o fugas de parámetros.
* Manejo correcto de transacciones concurrentes (`@Transactional` y bloqueos de fila).
* Cumplimiento del 90% de cobertura JaCoCo.
* Conformidad con la guía de estilo de Google.

### 4. Git Hooks Bloqueantes (`scripts/hooks/pre-push-gate.sh` e `install-hooks.sh`)
Hook que se instala en `.git/hooks/pre-push` y **cancela inmediatamente el comando `git push`** si:
* El código no pasa `mvn spotless:check`.
* Existen advertencias en Checkstyle (`google_checks.xml`).
* Falla alguna prueba unitaria (`mvn test`).
* La cobertura de código calculada por JaCoCo es menor al 90%.

---

## ⚡ Manejo de Concurrencia y Aislamiento

**El Reto:** Si dos transferencias entran en el mismo milisegundo por $4,000 MXN cada una, teniendo la cuenta solo $5,000 MXN disponibles, ambos hilos podrían leer $5,000 y aprobar ambas transacciones, generando un sobregiro de $3,000 MXN.

**La Solución Implementada:**
```java
// En el repositorio Spring Data JPA
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM AccountChannelLimitEntity a WHERE a.accountId = :accountId AND a.channel = :channel")
Optional<AccountChannelLimitEntity> findByAccountIdAndChannelForUpdate(
    @Param("accountId") String accountId,
    @Param("channel") ChannelType channel
);
```
* **PostgreSQL:** Ejecuta una sentencia atómica `SELECT ... FOR UPDATE` sobre la fila del canal de la cuenta.
* **Efecto:** El Hilo 1 adquiere el bloqueo exclusivo; el Hilo 2 espera. El Hilo 1 valida, consume y hace commit. Al liberarse el lock, el Hilo 2 lee el saldo restante real actualizado ($1,000) y rechaza la operación inmediatamente.

---

## 📝 Motor de Mensajes Dinámicos (Apache Commons Text)

Los mensajes de rechazo se gestionan de forma desacoplada y auditable mediante plantillas con `StringSubstitutor`:

```java
String template = "Operación rechazada para la cuenta ${accountId} en canal ${channelName}. "
    + "El monto de ${amount} excede el saldo diario disponible (${available}) por una diferencia de ${diff}.";

Map<String, Object> values = Map.of(
    "accountId", accountId,
    "channelName", channel.getDisplayName(),
    "amount", formatCurrency(amount),
    "available", formatCurrency(availableDaily),
    "diff", formatCurrency(diff)
);

String detailMessage = StringSubstitutor.replace(template, values);
```

---

## 🚨 Errores Estándar RFC 9457 (Problem Details)

Toda respuesta de error en el microservicio implementa la especificación RFC 9457:

```json
{
  "type": "https://api.banamex.com/errors/daily-limit-exceeded",
  "title": "Daily Limit Exceeded",
  "status": 422,
  "detail": "Operación rechazada para la cuenta 8840-3101 en canal App Móvil. El monto de $7,000.00 excede el saldo diario disponible ($5,000.00) por una diferencia de $2,000.00.",
  "instance": "/api/v1/accounts/8840-3101/transfer-validations",
  "invalidParams": [
    {
      "name": "amount",
      "reason": "Supera saldo diario disponible de $5,000.00"
    }
  ],
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-11-19T23:45:00Z"
}
```

---

## 🔭 Trazabilidad Distribuida (OpenTelemetry)

* Cada solicitud genera un contexto de traza distribuida bajo el estándar **W3C Trace Context**.
* Las cabeceras `traceparent` (`00-{traceId}-{spanId}-01`) se propagan en cada respuesta HTTP y en los logs estructurados JSON para trazabilidad de extremo a extremo.

---

## 📡 Endpoints Mínimos del Contrato OpenAPI 3.0

| Método | Endpoint | Descripción | Códigos HTTP |
| :--- | :--- | :--- | :--- |
| **GET** | `/api/v1/accounts/{accountId}/limits` | Consulta límites y consumo acumulado del día por canal | `200`, `400`, `404` |
| **PUT** | `/api/v1/accounts/{accountId}/limits/{channelId}` | Actualiza límites diarios y por operación de un canal | `200`, `400`, `422` |
| **POST** | `/api/v1/accounts/{accountId}/transfer-validations` | Valida y consume límite atómicamente (*Pessimistic Lock*) | `200`, `201`, `422` |
| **GET** | `/actuator/health` | Estado de salud del servicio y dependencias | `200`, `503` |

---

## 🚀 Pasos para Ejecutar el Proyecto

### Requisitos Previos
* Java 21 LTS (o superior).
* Git.

### 1. Clonar el Repositorio
```bash
git clone https://github.com/edgarcdmx/MS-TRANSFER-LIMITS.git
cd MS-TRANSFER-LIMITS
```

### 2. Instalar los Git Hooks del Harness
```bash
bash scripts/install-hooks.sh
```

### 3. Compilar y Ejecutar Pruebas Unitarias
```bash
./mvnw clean test
```
*(Verifica que Spotless, Checkstyle y JaCoCo se cumplan al 100%).*

### 4. Ejecutar el Microservicio en Local
* **Opción con PostgreSQL local / Docker:**
  ```bash
  docker compose up -d postgres
  ./mvnw spring-boot:run
  ```
* **Opción Ligera sin Docker (Perfil H2 en memoria compatible):**
  ```bash
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
  ```

---

## 🐳 Contenedorización Docker

El proyecto incluye un `Dockerfile` multi-etapa optimizado para producción bancaria:
```bash
# Construir imagen Docker
docker build -t banamex/ms-transfer-limits:latest .

# Levantar microservicio y base de datos con Compose
docker compose up -d
```

---

## 🧪 Cobertura de Pruebas (JaCoCo 90%+)

El proyecto utiliza **JaCoCo** configurado en el `pom.xml` con un umbral de bloqueo estricto del 90% sobre líneas y ramas (`BUNDLE` / `INSTRUCTION` / `BRANCH`).

Para generar el reporte de cobertura:
```bash
./mvnw clean test jacoco:report
```
El reporte HTML resultante se encuentra en `target/site/jacoco/index.html`.

---

## 👤 Información del Proyecto
* **Organización:** Banamex • Dirección de Arquitectura & Ingeniería
* **Autor / Responsable:** Edgar Rosas Samaro
* **Repositorio Oficial:** [https://github.com/edgarcdmx/MS-TRANSFER-LIMITS](https://github.com/edgarcdmx/MS-TRANSFER-LIMITS)
* **Demo Pública:** [https://edgar-ia-expert.web.app/Banamex_Caso_Practico.html](https://edgar-ia-expert.web.app/Banamex_Caso_Practico.html)
