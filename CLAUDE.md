# CLAUDE.md — MS-TRANSFER-LIMITS

Guidance for Claude Code (and any agent) working in this repository. Code, identifiers and commit
messages are in **English**; `README.md` and `docs/` are in **Spanish**.

## Purpose

Spring Boot microservice that decides whether a bank transfer is allowed under the customer's
per-channel limits (`MOBILE_APP`, `WEB`, `BRANCH`): a **per-operation limit** and a **daily limit**.
An approved validation records the consumption in the same transaction; anything that would exceed
a limit is rejected with an RFC 9457 Problem Detail. Validations are idempotent by `transferId`.

Endpoints (contract: `src/main/resources/openapi/openapi.yaml`):

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/accounts/{accountId}/limits` | 200, 400, 404 |
| PUT | `/api/v1/accounts/{accountId}/limits/{channelId}` | 200, 400, 422 |
| POST | `/api/v1/accounts/{accountId}/transfer-validations` | 201 approved, 200 idempotent replay, 400, 404, 409, 422 rejected |
| GET | `/actuator/health` | 200 / 503 |

## Stack

Java 27 · Spring Boot 4.2.0 · Maven Wrapper 3.9.16 (`./mvnw`, never a global `mvn`) · PostgreSQL +
Spring Data JPA + Flyway (`ddl-auto=validate`) · springdoc-openapi 3 · Apache Commons Text ·
OpenTelemetry via `spring-boot-starter-opentelemetry` (OTLP, endpoint from
`OTEL_EXPORTER_OTLP_ENDPOINT`) · JUnit 5 + Mockito + AssertJ.

## Architecture map (hexagonal, base package `com.banamex.transferlimits`)

```
api/            REST controllers, request/response DTO records, mappers, api/error (ProblemDetail advice)
application/    use cases (ValidateTransferUseCase, GetAccountLimitsUseCase, UpdateChannelLimitUseCase)
domain/         pure Java: Channel, ChannelLimit, LimitDecision (Approved/Rejected), LimitPolicy...
domain/port/    outbound ports (LimitRepository, ConsumptionRepository, TransferValidationRepository)
infrastructure/ adapters: persistence/ (JPA entities, native atomic queries), time/ (BusinessDateProvider)
config/         Spring wiring only (beans, Clock, observation/OTel setup)
```

Dependency rule: `api -> application -> domain <- infrastructure`. `domain` has **no Spring, JPA,
Jackson or servlet imports**. Controllers never touch repositories; adapters never contain business
rules.

Key files:

- `src/main/resources/openapi/openapi.yaml` — the contract (source of truth, OpenAPI 3.1).
- `src/main/resources/db/migration/V*__*.sql` — Flyway migrations (never edit an applied one; add `V<n+1>`).
- `src/main/resources/messages/rejections.properties` — rejection message templates (Commons Text).
- `docs/adr/` — ADR-001 hexagonal, ADR-002 concurrency, ADR-003 contract-first.
- `config/checkstyle/google_checks.xml` — Checkstyle rules.

## Commands

```bash
./mvnw spotless:apply             # format (google-java-format 2.0.0, Google style)
./mvnw -B verify                  # THE gate: compile, unit tests, spotless:check, checkstyle, jacoco:check
bash scripts/quality-gate.sh      # same gate, staged, with fix hints (exit 11/12/13/14 per stage)
./mvnw -B test -Dtest=Class#method
./mvnw spring-boot:run            # needs PostgreSQL (see README / compose.yaml)
bash scripts/install-hooks.sh     # once per clone: git config core.hooksPath .githooks
```

On this Windows machine the JDK is portable: `export JAVA_HOME="$USERPROFILE/.local/devtools/jdk/<jdk-27-dir>"`
(the hook scripts find it automatically). Use `mvnw.cmd` only from PowerShell/cmd.

## Coding rules

- **Google Java Style**, enforced by Spotless (`spotless:check`) and Checkstyle `google_checks.xml`
  with `failOnViolation=true` and `violationSeverity=warning` (warnings fail the build). Javadoc on
  public types/methods, no wildcard imports, 100-column lines, camelCase test names (no `_`).
- **Contract-first**: change `openapi.yaml` first, then DTOs/controllers to match it exactly (paths,
  status codes, schemas, `accountId` spelling). Use the `new-endpoint` skill.
- **Money**: `BigDecimal` only, scale 2 (`RoundingMode.HALF_EVEN` when scaling), `NUMERIC(19,2)` in
  DB, compare with `compareTo`, never `equals`/`double`/`float`. Amount must be > 0. Currency `MXN`.
- **Time**: always through the injected `java.time.Clock` / `BusinessDateProvider` (business zone
  `America/Mexico_City`, configurable). Never `LocalDate.now()` / `Instant.now()` without a Clock.
- **Errors**: RFC 9457 `ProblemDetail`, `Content-Type: application/problem+json`,
  `type = https://api.banamex.example/problems/<kebab-code>`, plus `title`, `status`, `detail`,
  `instance` and extensions `code`, `traceId`, `errors[]`. 500s never leak exception messages or
  stack traces. Catalogue and how to add a code: `problem-details` skill.
- **Rejection messages**: Apache Commons Text `StringSubstitutor` over templates in
  `messages/rejections.properties` (amounts formatted `es-MX`). No string concatenation of messages.
- **Concurrency**: the daily limit is guaranteed by a single atomic conditional
  `UPDATE daily_consumption SET consumed = consumed + :amount WHERE ... AND consumed + :amount <= :dailyLimit`
  inside one `@Transactional` (READ COMMITTED). Do not replace it with read-then-write, `SELECT ... FOR
  UPDATE` or optimistic retries without a new ADR (see `docs/adr/ADR-002-concurrencia.md`).
- **SQL**: bind parameters only; never build SQL with string concatenation.
- **Observability**: keep `traceId`/`spanId` in logs and `traceId` in Problem Details; mask account
  ids in span attributes and logs; never log full request bodies, tokens or secrets.
- Constructor injection only; records for DTOs and value objects; `final` classes where sensible.

## Testing rules

- **Unit tests and `@WebMvcTest` slices only** (JUnit 5, Mockito, AssertJ). `src/test/**` mirrors
  `src/main/**` packages.
- **NO integration tests**: no Testcontainers, no `@SpringBootTest` with a real datasource, no
  `maven-failsafe-plugin`, no embedded DB tests. This is an explicit project requirement (R11).
- **Coverage**: JaCoCo LINE **and** BRANCH >= 0.90, enforced by `jacoco:check` in `verify`. Raise
  coverage by testing business branches (limit boundaries, idempotency, each rejection reason, each
  Problem Detail), never by lowering thresholds or widening `<excludes>` (only the `*Application`
  bootstrap and pure `config/` wiring are excluded).
- Concurrency is proven with an in-memory adapter reproducing the conditional UPDATE
  (`ConcurrentHashMap.compute`) and >= 50 threads (`ExecutorService` + `CountDownLatch`).
- Time-dependent tests use `Clock.fixed(...)`.

## Definition of done

1. `openapi.yaml` and code agree; new behaviour has unit tests.
2. `bash scripts/quality-gate.sh` (or `./mvnw -B verify`) is green: format, Checkstyle, tests,
   coverage >= 90 % lines and branches.
3. Changes committed (Conventional Commits, English).
4. `code-security-reviewer` subagent verdict **APPROVED** for the current `HEAD`
   (it writes `target/review/REVIEW_OK`).
5. README/ADRs updated when behaviour or decisions change.

## Mandatory pre-push flow

1. Run the **`quality-gate`** skill until it passes; fix failures at the root cause.
2. Commit.
3. Run the **`code-security-reviewer`** subagent. CRITICAL/HIGH findings -> fix, commit, repeat from 1.
   Only an APPROVED verdict writes `target/review/REVIEW_OK` = `git rev-parse HEAD`.
4. `git push` (normal push, through the hooks). Any new commit invalidates the approval.

Do not run `./mvnw clean` between review and push: it deletes `target/review/REVIEW_OK`.

## Hooks (what will happen to you)

Configured in `.claude/settings.json`; scripts in `scripts/hooks/` (bash, run by Git Bash on Windows):

| Event | Script | Behaviour |
|---|---|---|
| PreToolUse `Bash\|PowerShell` | `pre-push-gate.sh` | If the command is a git push (normalized: quotes stripped, line continuations joined, `git`/`git.exe` with any path, `-C`/`-c` options, any case): first requires `target/review/REVIEW_OK` == `HEAD` (fail fast), then runs `scripts/quality-gate.sh`. Blocks outright: `--no-verify` anywhere, `core.hooksPath` or `alias.` together with `push`, `eval`/`Invoke-Expression` or `$VAR`/`$(...)` expansion followed by `push`. Otherwise exits 0 immediately. Failure = exit 2 = tool call **blocked**, reason on stderr. |
| PreToolUse `Edit\|Write\|MultiEdit\|NotebookEdit` | `protect-files.sh` | Blocks writes to `.env*` (only the placeholder template `.env.example` is allowed), `*.pem`, `*.key`, anything under `target/` or `.git/` (exit 2). |
| PostToolUse `Edit\|Write\|MultiEdit` | `format-java.sh` | For `.java` files runs `./mvnw -q spotless:apply -DspotlessFiles=<file regex>`. Never fails the edit. Re-read a file before editing it again: the hook may have reformatted it. |
| Stop | `remind-gate.sh` | Non-blocking reminder (`systemMessage`) when Java changed and HEAD has no approval. |

Native git hook: `.githooks/pre-push` requires every pushed commit (each local sha on stdin) to be
the one in `REVIEW_OK` and that to be `HEAD`, then runs the gate, for pushes made outside Claude Code
(activate with `bash scripts/install-hooks.sh`). `settings.json` also denies the GitHub MCP write
tools that would bypass git hooks (`push_files`, `create_or_update_file`, `delete_file`,
`merge_pull_request`). Regression tests: `bash scripts/hooks/test-hooks.sh`. When a hook blocks you,
fix the cause; never try to work around it.

**Hooks are guardrails, not a security boundary.** They stop accidental and casual bypasses, but
command detection is heuristic: indirection such as variables (`G=git; $G push`), `eval`, encoded
strings, aliases defined in an earlier command, or a script written to disk and executed cannot be
detected reliably (the common forms are blocked heuristically; the rest is residual risk). Real
enforcement must live on the remote: GitHub branch protection on `main` with required CI status
checks (`./mvnw -B verify`) and required reviews.

## Skills and subagent

- `quality-gate` — run/interpret the gate and fix format, Checkstyle, test and coverage failures.
- `new-endpoint` — contract-first flow for adding/changing an endpoint.
- `problem-details` — error catalogue (codes, statuses, type URIs) and how to add a new error.
- `docker-release` — build the image, `docker compose up`, check health and traces in Jaeger.
- Subagent `code-security-reviewer` — read-only review of the branch diff; mandatory before push/PR.

## MCP servers (`.mcp.json`, no secrets; token via `GITHUB_PERSONAL_ACCESS_TOKEN` env var)

| Server | Use it for |
|---|---|
| `context7` | Current docs for Spring Boot 4.x, Spring Framework 7, Hibernate 7, springdoc 3, OTel starter, Commons Text. Use it before relying on memory for APIs that changed in Boot 4 (renamed starters, Jackson 3, test slices). |
| `fetch` | Verify artifact versions on Maven Central (`maven-metadata.xml`), read RFC 9457, Claude Code docs. |
| `github` | Repo `edgarcdmx/MS-TRANSFER-LIMITS`: PRs, branch protection, checking pushed state. |

If an MCP server does not connect, say so and use the fallback (CLI `gh`, `curl`); never invent results.

## Never do

- `git push --no-verify`, `git commit --no-verify`, `--force` / `-f` pushes, rewriting `main` history.
- Disable, skip or weaken gates: `-DskipTests`, `-Dspotless.check.skip`, `-Dcheckstyle.skip`,
  `-Djacoco.skip`, lowering `jacoco.minimum.*`, adding excludes to pass coverage, editing hooks/settings
  to get past a block.
- Commit secrets, `.env` files, keys, certificates or real customer data; hardcode credentials
  (use environment variables).
- Add integration tests (Testcontainers, Failsafe, `@SpringBootTest` + DB) — out of scope by design.
- Add dependency scanners (OWASP dependency-check, Snyk, Dependabot, Renovate security, etc.) —
  intentionally excluded (R12).
- Use `double`/`float` for money, `now()` without `Clock`, or SQL string concatenation.
- Edit an already-applied Flyway migration, or `target/` / `.git/` by hand.
- Write `target/review/REVIEW_OK` yourself — only the `code-security-reviewer` subagent does it, and
  only on APPROVED.
