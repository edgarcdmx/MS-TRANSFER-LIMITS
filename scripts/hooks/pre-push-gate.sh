#!/usr/bin/env bash
# Claude Code PreToolUse hook (matcher: Bash|PowerShell).
# If the command is a `git push`, run scripts/quality-gate.sh and require
# target/review/REVIEW_OK == `git rev-parse HEAD` (written by the code-security-reviewer subagent).
# Exit 0 = allow (normal permission flow continues); exit 2 = block, stderr is shown to Claude.
set -u

HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
. "$HOOK_DIR/../lib/common.sh"

INPUT="$(cat)"
CMD="$(printf '%s' "$INPUT" | json_string_field command)"

# Fast path: not a git push -> nothing to do.
# Matches `git push`, `git -C dir push`, `git --no-pager push`, and pushes inside && / ; chains.
PUSH_RE='(^|[^[:alnum:]_-])git([[:space:]]+-C[[:space:]]+[^[:space:]]+|[[:space:]]+-c[[:space:]]+[^[:space:]]+|[[:space:]]+--?[[:alnum:]-]+(=[^[:space:]]*)?)*[[:space:]]+push([[:space:]]|$|[;&|)])'
if ! printf '%s' "$CMD" | grep -qE "$PUSH_RE"; then
  exit 0
fi

# Locate the repository the push targets: session cwd from the hook payload, then
# CLAUDE_PROJECT_DIR, then the repo that contains this script.
CWD="$(printf '%s' "$INPUT" | json_string_field cwd)"
ROOT=""
[ -n "$CWD" ] && ROOT="$(repo_root_for "$CWD")"
[ -z "$ROOT" ] && [ -n "${CLAUDE_PROJECT_DIR:-}" ] && ROOT="$(repo_root_for "$CLAUDE_PROJECT_DIR")"
[ -z "$ROOT" ] && ROOT="$(repo_root_for "$HOOK_DIR")"
if [ -z "$ROOT" ]; then
  qg_err "BLOCKED by pre-push-gate: cannot locate the git repository to validate before push."
  exit 2
fi

GATE="$ROOT/scripts/quality-gate.sh"
if [ ! -f "$GATE" ]; then
  qg_err "BLOCKED by pre-push-gate: $GATE not found."
  exit 2
fi

qg_err "[pre-push-gate] git push detected; running quality gate in $ROOT ..."
# The gate's progress goes to stderr so Claude sees it if we block.
bash "$GATE" 1>&2
rc=$?
if [ "$rc" -ne 0 ]; then
  qg_err "BLOCKED by pre-push-gate: quality gate failed (exit $rc). Fix the issues above, commit,"
  qg_err "  re-run the code-security-reviewer subagent, then push again. Never use --no-verify."
  exit 2
fi

if ! review_ok_matches_head "$ROOT"; then
  qg_err "BLOCKED by pre-push-gate: push requires an APPROVED review for the current HEAD."
  exit 2
fi

qg_err "[pre-push-gate] quality gate PASSED and review APPROVED for HEAD; push allowed."
exit 0
