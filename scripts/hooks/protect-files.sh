#!/usr/bin/env bash
# Claude Code PreToolUse hook (matcher: Edit|Write|MultiEdit|NotebookEdit).
# Blocks writes to secrets (.env* except the .env.example template, *.pem, *.key), build output (target/) and git internals (.git/).
# Exit 0 = allow; exit 2 = block (stderr shown to Claude).
set -u

HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
. "$HOOK_DIR/../lib/common.sh"

INPUT="$(cat)"
FILE="$(printf '%s' "$INPUT" | json_string_field file_path)"
[ -z "$FILE" ] && FILE="$(printf '%s' "$INPUT" | json_string_field notebook_path)"
[ -z "$FILE" ] && exit 0

PATH_FWD="$(to_slashes "$FILE")"
BASE="${PATH_FWD##*/}"

# Path relative to the repository root (if the file lives inside one).
REL="$PATH_FWD"
ROOT="$(repo_root_for "$PATH_FWD")"
if [ -n "$ROOT" ]; then
  root_lc="$(printf '%s' "$ROOT" | tr '[:upper:]' '[:lower:]')"
  path_lc="$(printf '%s' "$PATH_FWD" | tr '[:upper:]' '[:lower:]')"
  case "$path_lc" in
    "$root_lc"/*) REL="${PATH_FWD:${#ROOT}+1}" ;;
  esac
fi
REL="${REL#./}"

block() {
  qg_err "BLOCKED by protect-files: $1"
  qg_err "  File: $FILE"
  exit 2
}

case "$BASE" in
  # The committed template with placeholder values is the only allowed .env* file.
  .env.example) ;;
  .env | .env.* | .env*) block "secrets files (.env*) must never be written by the agent; use environment variables." ;;
  *.pem | *.key) block "private keys/certificates (*.pem, *.key) must never be written to the repository." ;;
esac

case "/$REL/" in
  /target/* | */target/*) block "target/ is build output; change sources and rebuild instead (REVIEW_OK is written only by the reviewer via Bash)." ;;
esac

case "/$PATH_FWD/" in
  */.git/*) block ".git/ internals must not be edited; use git commands." ;;
esac

exit 0
