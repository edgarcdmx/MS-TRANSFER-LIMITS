---
name: quality-gate
description: Run and fix the MS-TRANSFER-LIMITS quality gate (Google Java Format via Spotless, Checkstyle google_checks, unit tests, JaCoCo LINE+BRANCH >= 90%). Use before every commit/push, when a git push was blocked by the pre-push hook, when `./mvnw verify` fails, or when the user asks to "run the gate", "check quality", "fix checkstyle/format/coverage".
---

# Quality gate

The gate is `scripts/quality-gate.sh` (same script the Claude `pre-push-gate.sh` hook and the git
`.githooks/pre-push` hook run). It works from any directory and finds the JDK by itself.

```bash
bash scripts/quality-gate.sh
```

| Exit | Stage | Meaning |
|---|---|---|
| 0 | — | PASSED |
| 10 | env | not a git repo, no `./mvnw`, or no JDK (set `JAVA_HOME`) |
| 11 | 1 `spotless:check` | formatting differs from google-java-format |
| 12 | 2 `checkstyle:check` | `config/checkstyle/google_checks.xml` violation (warnings fail) |
| 13 | 3 `verify` | compilation error or failing unit test (or packaging) |
| 14 | 4 `jacoco:check` | LINE or BRANCH coverage < 0.90 |

Logs: `target/quality-gate/{1-spotless,2-checkstyle,3-verify}.log`. The script prints the relevant
`[ERROR]`/`[WARN]` lines on failure.

## Fix loop

Repeat until exit 0. Fix root causes; never skip stages.

1. **Exit 11 (format)**: `./mvnw spotless:apply`, inspect `git diff`, re-run. If spotless reports
   "Unable to format file", the file has a syntax error: fix compilation first.
2. **Exit 12 (checkstyle)**: open each `file:line [Rule]`. Typical rules and fixes:
   - `MissingJavadocType/Method`, `JavadocParagraph`, `SummaryJavadoc` -> add/repair Javadoc
     (first sentence ends with a period; `<p>` on its own paragraph).
   - `AbbreviationAsWordInName` -> `TransferId`, not `TransferID`.
   - `GoogleMethodName` / `MethodName` in tests -> camelCase, e.g. `rejectsWhenDailyLimitExceeded`.
   - `CustomImportOrder`, `AvoidStarImport` -> run `spotless:apply`; remove wildcard imports.
   - `LineLength` (100) -> spotless reflows code; split long strings/annotations manually.
   - `OverloadMethodsDeclarationOrder`, `VariableDeclarationUsageDistance` -> reorder code.
3. **Exit 13 (build/tests)**: read `target/surefire-reports/*.txt`; reproduce with
   `./mvnw -B test -Dtest=ClassName#method`. A failing test reveals either a bug (fix `src/main`) or
   a wrong expectation (fix the test only if the contract/ADR says so).
4. **Exit 14 (coverage)**: open `target/site/jacoco/index.html` (or `jacoco.csv`), find missed
   lines/branches in `domain`, `application`, `api`, `infrastructure`. Add unit tests for the
   business branches: per-operation boundary (`==` limit approves, `+0.01` rejects), daily boundary,
   idempotent repeat, 404 without limits, each Problem Detail, `BigDecimal` scale handling,
   `Clock.fixed` date rollover.

## Rules

- Never pass `-DskipTests`, `-Dspotless.check.skip`, `-Dcheckstyle.skip`, `-Djacoco.skip`, never
  lower `jacoco.minimum.*` and never add JaCoCo excludes to pass.
- Never add integration tests (Testcontainers, Failsafe, `@SpringBootTest` with a datasource).
- Don't `./mvnw clean` after the reviewer approved: it deletes `target/review/REVIEW_OK`.
- When green: commit, then run the `code-security-reviewer` subagent (required before push).
- `QG_REUSE=1 bash scripts/quality-gate.sh` skips the run if it already passed for the exact same
  HEAD and working tree (used by the git hook to avoid running the build twice).
