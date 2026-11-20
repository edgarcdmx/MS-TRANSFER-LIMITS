#!/usr/bin/env bash
# Regression tests for the Claude Code hooks (pre-push-gate.sh, protect-files.sh).
# Pipes sample PreToolUse JSON payloads into each hook and asserts the exit code.
# Fast: push payloads point at a throw-away git repo without target/review/REVIEW_OK, so the
# pre-push gate blocks on the (first) approval check and never starts the Maven build.
#   bash scripts/hooks/test-hooks.sh        -> exit 0 when every case passes
set -u

HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$HOOK_DIR" rev-parse --show-toplevel)"
PRE_PUSH="$HOOK_DIR/pre-push-gate.sh"
PROTECT="$HOOK_DIR/protect-files.sh"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
git -C "$SANDBOX" init -q
git -C "$SANDBOX" -c user.name=t -c user.email=t@example.invalid commit -q --allow-empty -m init

pass=0
fail=0

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\t'/\\t}"
  printf '%s' "$s"
}

# check <expected exit> <label> <hook> <payload>
check() {
  local expected="$1" label="$2" hook="$3" payload="$4" actual err
  err="$(printf '%s' "$payload" | bash "$hook" 2>&1 >/dev/null)"
  actual=$?
  if [ "$actual" -eq "$expected" ]; then
    pass=$((pass + 1))
    printf 'PASS  exit=%s  %s\n' "$actual" "$label"
  else
    fail=$((fail + 1))
    printf 'FAIL  exit=%s (expected %s)  %s\n' "$actual" "$expected" "$label"
    printf '%s\n' "$err" | sed 's/^/        /'
  fi
}

# cmd <expected exit> <tool> <command>
cmd() {
  local expected="$1" tool="$2" command="$3"
  check "$expected" "[$tool] $command" "$PRE_PUSH" \
    "{\"session_id\":\"test\",\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"$tool\",\"tool_input\":{\"command\":\"$(json_escape "$command")\",\"description\":\"test\"},\"cwd\":\"$(json_escape "$SANDBOX")\"}"
}

# file <expected exit> <path relative to the repo root>
file() {
  local expected="$1" rel="$2"
  check "$expected" "[Write] $rel" "$PROTECT" \
    "{\"session_id\":\"test\",\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Write\",\"tool_input\":{\"file_path\":\"$(json_escape "$REPO_ROOT/$rel")\",\"content\":\"x\"},\"cwd\":\"$(json_escape "$REPO_ROOT")\"}"
}

echo "== pre-push-gate.sh: pushes and bypass attempts must be blocked (exit 2)"
cmd 2 Bash 'git push'
cmd 2 Bash 'git push origin main'
cmd 2 Bash 'git.exe push'
cmd 2 Bash "'git' push"
cmd 2 Bash '"git" push'
cmd 2 Bash $'git \\\npush'
cmd 2 Bash 'g\it push'
cmd 2 Bash 'GIT PUSH'
cmd 2 Bash 'git -c alias.p=push p'
cmd 2 Bash 'git config alias.p push && git p'
cmd 2 Bash 'git.exe -c core.hooksPath=/dev/null push'
cmd 2 Bash 'git -c core.hookspath= push origin main'
cmd 2 Bash 'git push --no-verify'
cmd 2 Bash 'git commit --no-verify -m x'
cmd 2 Bash 'git -C /some/dir push'
cmd 2 Bash 'git --no-pager -c color.ui=never push'
cmd 2 Bash 'git --git-dir .git push'
cmd 2 Bash 'cd repo && git push -u origin feature'
cmd 2 Bash '/usr/bin/git push'
cmd 2 Bash '"C:\Program Files\Git\cmd\git.exe" push origin main'
cmd 2 Bash '(git push)'
cmd 2 Bash 'G=git; $G push'
cmd 2 Bash '${GIT} push'
cmd 2 Bash 'eval "git push"'
cmd 2 PowerShell '& "git.exe" push'
cmd 2 PowerShell $'git `\npush origin main'
cmd 2 PowerShell 'Invoke-Expression "git push"'
cmd 2 PowerShell 'git push --no-verify'

echo "== pre-push-gate.sh: benign commands must pass (exit 0)"
cmd 0 Bash 'git status'
cmd 0 Bash 'echo git pushy'
cmd 0 Bash 'git commit -m "push later"'
cmd 0 Bash 'git log --oneline -5'
cmd 0 Bash 'git add src/main/java/Foo.java'
cmd 0 Bash './mvnw -B verify'
cmd 0 Bash 'echo $HOME'
cmd 0 Bash 'grep -rn pushed_shas .githooks'
cmd 0 Bash 'git stash push -m wip'
cmd 0 PowerShell 'Get-ChildItem'

echo "== protect-files.sh"
file 2 '.env.example'
file 2 '.env'
file 2 '.env.local'
file 2 '.env.production'
file 2 '.env.example.bak'
file 2 'config/server.pem'
file 2 'config/server.key'
file 2 'target/review/REVIEW_OK'
file 0 'src/main/java/com/banamex/transferlimits/Foo.java'
file 0 'compose.yaml'

echo "== .githooks/pre-push (native hook, run inside the throw-away repo)"
mkdir -p "$SANDBOX/scripts/lib" "$SANDBOX/.githooks"
cp "$REPO_ROOT/scripts/lib/common.sh" "$SANDBOX/scripts/lib/"
cp "$REPO_ROOT/.githooks/pre-push" "$SANDBOX/.githooks/"
SANDBOX_HEAD="$(git -C "$SANDBOX" rev-parse HEAD)"
OTHER_SHA="1111111111111111111111111111111111111111"
ZERO_SHA="0000000000000000000000000000000000000000"
# native <expected exit> <expected stderr substring> <label> <stdin lines>
native() {
  local expected="$1" needle="$2" label="$3" lines="$4" actual err
  err="$(cd "$SANDBOX" && printf '%s\n' "$lines" | bash .githooks/pre-push 2>&1 >/dev/null)"
  actual=$?
  if [ "$actual" -eq "$expected" ] && [[ $err == *"$needle"* ]]; then
    pass=$((pass + 1))
    printf 'PASS  exit=%s  [pre-push] %s\n' "$actual" "$label"
  else
    fail=$((fail + 1))
    printf 'FAIL  exit=%s (expected %s)  [pre-push] %s\n' "$actual" "$expected" "$label"
  fi
}
native 0 "" "delete-only push" "(delete) $ZERO_SHA refs/heads/old $OTHER_SHA"
native 1 "approval missing" "no REVIEW_OK" "refs/heads/main $SANDBOX_HEAD refs/heads/main $ZERO_SHA"
mkdir -p "$SANDBOX/target/review"
printf '%s\n' "$SANDBOX_HEAD" >"$SANDBOX/target/review/REVIEW_OK"
native 1 "is not the commit approved" "pushed sha differs from REVIEW_OK (HEAD approved)" \
  "refs/heads/other $OTHER_SHA refs/heads/other $ZERO_SHA"
native 1 "refs/heads/other ($OTHER_SHA) is not the commit approved" "one of several pushed shas not approved" \
  "refs/heads/main $SANDBOX_HEAD refs/heads/main $ZERO_SHA
refs/heads/other $OTHER_SHA refs/heads/other $ZERO_SHA"

echo
echo "hook tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
