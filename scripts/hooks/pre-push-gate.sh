#!/usr/bin/env bash
# Claude Code PreToolUse hook (matcher: Bash|PowerShell).
# If the command is a `git push` (in any spelling classify_git_command recognises: git.exe,
# quoted, with a path, with -C/-c options, split by line continuations), require
# target/review/REVIEW_OK == `git rev-parse HEAD` (written by the code-security-reviewer subagent)
# and then run scripts/quality-gate.sh. The approval is checked FIRST (fail fast, no build).
# Push forms that evade the native hooks or cannot be analysed (--no-verify, core.hooksPath,
# alias.*, eval, $VAR push) are blocked outright.
# Exit 0 = allow (normal permission flow continues); exit 2 = block, stderr is shown to Claude.
#
# This is a guardrail, not a security boundary (see CLAUDE.md "Hooks"): real enforcement is
# GitHub branch protection with required CI checks.
set -u

HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
. "$HOOK_DIR/../lib/common.sh"

INPUT="$(cat)"
CMD="$(printf '%s' "$INPUT" | json_string_field command)"

VERDICT="$(classify_git_command "$CMD")"
case "$VERDICT" in
  none) exit 0 ;;
  forbidden:*)
    qg_err "BLOCKED by pre-push-gate: ${VERDICT#forbidden:}."
    qg_err "  Push with a plain 'git push' through the hooks after the gate and the reviewer pass."
    exit 2
    ;;
esac

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

# Fail fast: without an approval for HEAD the push is blocked anyway; skip the slow build.
if ! review_ok_matches_head "$ROOT"; then
  qg_err "BLOCKED by pre-push-gate: push requires an APPROVED review for the current HEAD."
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
  qg_err "  re-run the code-security-reviewer subagent, then push again. Never skip the hooks."
  exit 2
fi

qg_err "[pre-push-gate] review APPROVED for HEAD and quality gate PASSED; push allowed."
exit 0
