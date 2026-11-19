#!/usr/bin/env bash
# Quality gate for MS-TRANSFER-LIMITS. Single source of truth used by:
#   - the 'quality-gate' skill, the Claude Code PreToolUse hook (scripts/hooks/pre-push-gate.sh)
#   - the native git hook (.githooks/pre-push)
#
# Stages (fail fast):
#   1. spotless:check   -> Google Java Format            exit 11
#   2. checkstyle:check -> google_checks.xml             exit 12
#   3. verify (compile + unit tests + package)            exit 13
#   4. jacoco:check     -> LINE and BRANCH >= 0.90        exit 14
# Other exit codes: 0 = passed, 10 = environment problem (no git repo / no JDK / no mvnw).
#
# Options (env):
#   QG_REUSE=1  skip the run if the gate already passed for the exact same HEAD + working tree.
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
. "$SCRIPT_DIR/lib/common.sh"

ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null)"
if [ -z "$ROOT" ]; then
  qg_err "[quality-gate] ERROR: not inside a git work tree."
  exit 10
fi
cd "$ROOT" || exit 10

if [ ! -f ./mvnw ]; then
  qg_err "[quality-gate] ERROR: ./mvnw not found in $ROOT."
  exit 10
fi
ensure_java || exit 10

LOG_DIR="$ROOT/target/quality-gate"
mkdir -p "$LOG_DIR"
PASS_STAMP="$LOG_DIR/PASSED"

fingerprint() {
  {
    git rev-parse HEAD 2>/dev/null
    git status --porcelain=v1 --untracked-files=all 2>/dev/null
    git diff HEAD 2>/dev/null
  } | git hash-object --stdin
}

if [ "${QG_REUSE:-0}" = "1" ] && [ -f "$PASS_STAMP" ] \
  && [ "$(cat "$PASS_STAMP")" = "$(fingerprint)" ]; then
  echo "[quality-gate] PASSED (reused: gate already green for this exact HEAD and working tree)"
  exit 0
fi
rm -f "$PASS_STAMP"

# English, UTF-8 tool messages (checkstyle otherwise follows the OS locale).
export MAVEN_OPTS="${MAVEN_OPTS:-} -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8"
MVN=(./mvnw -B -ntp)

# run_stage <number> <name> <logfile> <maven args...>
run_stage() {
  local num="$1" name="$2" log="$3"
  shift 3
  echo "[quality-gate] stage $num/4: $name ..."
  if "${MVN[@]}" "$@" >"$log" 2>&1; then
    echo "[quality-gate] stage $num/4: $name OK"
    return 0
  fi
  qg_err "[quality-gate] stage $num/4: $name FAILED (full log: $log)"
  qg_err "----- last lines of $log -----"
  grep -E '\[(ERROR|WARN(ING)?)\]' "$log" \
    | grep -vE '^\[ERROR\] \[Help 1\]|^\[ERROR\] *$|Re-run Maven|re-run Maven|full stack trace|For more information about the errors|^\[ERROR\] -> \[Help' \
    | tail -n 40 >&2
  qg_err "------------------------------"
  return 1
}

# 1. Formatting
if ! run_stage 1 "spotless:check (Google Java Format)" "$LOG_DIR/1-spotless.log" spotless:check; then
  qg_err "HOW TO FIX: run './mvnw spotless:apply', review the diff, commit, re-run the gate."
  exit 11
fi

# 2. Static style rules
if ! run_stage 2 "checkstyle:check (google_checks.xml, warnings fail)" "$LOG_DIR/2-checkstyle.log" checkstyle:check; then
  qg_err "HOW TO FIX: fix each reported file:line by hand (Javadoc, naming, import order, line length 100,"
  qg_err "  abbreviations, overload order...). spotless:apply fixes layout only, not naming/Javadoc rules."
  exit 12
fi

# 3 + 4. Build, unit tests, coverage (spotless/checkstyle already ran above).
# Selective clean instead of `mvn clean` (which would also delete target/review/REVIEW_OK):
# stale classes or an appended jacoco.exec from earlier runs could otherwise hide coverage drops.
rm -rf "$ROOT/target/classes" "$ROOT/target/test-classes" "$ROOT/target/jacoco.exec" \
  "$ROOT/target/surefire-reports" "$ROOT/target/site/jacoco"
VERIFY_LOG="$LOG_DIR/3-verify.log"
if ! run_stage 3 "verify (compile, unit tests, package, jacoco:check)" "$VERIFY_LOG" \
  verify -Dspotless.check.skip=true -Dcheckstyle.skip=true; then
  if grep -qE 'Coverage checks have not been met|jacoco-maven-plugin:[^ ]*:check' "$VERIFY_LOG" \
    && ! grep -qE 'There are test failures|Tests run:.*(Failures: [1-9]|Errors: [1-9])|COMPILATION ERROR' "$VERIFY_LOG"; then
    qg_err "[quality-gate] stage 4/4: jacoco:check FAILED (LINE and BRANCH must be >= 0.90)"
    qg_err "HOW TO FIX: open target/site/jacoco/index.html, find uncovered business branches and add unit"
    qg_err "  tests (JUnit 5 + Mockito + AssertJ, @WebMvcTest slices). Do NOT lower thresholds or add"
    qg_err "  excludes to dodge the rule; never add integration tests (Testcontainers/@SpringBootTest+DB)."
    exit 14
  fi
  qg_err "HOW TO FIX: compilation or unit-test failure. See surefire reports in target/surefire-reports/."
  qg_err "  Re-run a single test: ./mvnw -B test -Dtest=ClassName#method"
  exit 13
fi
echo "[quality-gate] stage 4/4: jacoco:check OK (coverage report: target/site/jacoco/index.html)"

fingerprint >"$PASS_STAMP"
echo "[quality-gate] PASSED"
exit 0
