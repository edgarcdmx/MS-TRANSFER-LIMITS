#!/usr/bin/env bash
# Claude Code Stop hook. Non-blocking reminder: if Java sources changed (uncommitted, untracked,
# or committed after the last approved review) and target/review/REVIEW_OK does not match HEAD,
# print a JSON systemMessage reminding to run the quality gate and the reviewer subagent.
# Always exits 0.
set -u

HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
. "$HOOK_DIR/../lib/common.sh"

INPUT="$(cat)"
CWD="$(printf '%s' "$INPUT" | json_string_field cwd)"
ROOT=""
[ -n "$CWD" ] && ROOT="$(repo_root_for "$CWD")"
[ -z "$ROOT" ] && [ -n "${CLAUDE_PROJECT_DIR:-}" ] && ROOT="$(repo_root_for "$CLAUDE_PROJECT_DIR")"
[ -z "$ROOT" ] && ROOT="$(repo_root_for "$HOOK_DIR")"
[ -z "$ROOT" ] && exit 0

# Approved for the current HEAD and nothing pending in the working tree -> silent.
WORKTREE_JAVA="$(git -C "$ROOT" status --porcelain=v1 --untracked-files=all 2>/dev/null | grep -cE '\.java"?$')"
if review_ok_matches_head "$ROOT" 2>/dev/null && [ "$WORKTREE_JAVA" -eq 0 ]; then
  exit 0
fi

# Java changes committed since the last approved commit (or vs origin/main when never reviewed).
COMMITTED_JAVA=0
APPROVED="$(tr -d '[:space:]' 2>/dev/null <"$ROOT/target/review/REVIEW_OK")"
BASE=""
if [ -n "$APPROVED" ] && git -C "$ROOT" cat-file -e "$APPROVED^{commit}" 2>/dev/null; then
  BASE="$APPROVED"
elif git -C "$ROOT" rev-parse -q --verify origin/main >/dev/null 2>&1; then
  BASE="$(git -C "$ROOT" merge-base origin/main HEAD 2>/dev/null)"
fi
if [ -n "$BASE" ]; then
  COMMITTED_JAVA="$(git -C "$ROOT" diff --name-only "$BASE" HEAD 2>/dev/null | grep -c '\.java$')"
elif [ -n "$(git -C "$ROOT" ls-files '*.java' 2>/dev/null | head -n 1)" ]; then
  COMMITTED_JAVA=1 # no baseline at all: tracked Java code has never been reviewed
fi

if [ "$WORKTREE_JAVA" -eq 0 ] && [ "$COMMITTED_JAVA" -eq 0 ]; then
  exit 0
fi

printf '%s\n' '{"systemMessage":"Reminder: Java sources changed and there is no code-security-reviewer approval for HEAD. Before pushing: run the quality-gate skill (bash scripts/quality-gate.sh), commit, then run the code-security-reviewer subagent (it writes target/review/REVIEW_OK). git push is blocked until both pass."}'
exit 0
