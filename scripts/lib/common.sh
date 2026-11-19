# shellcheck shell=bash
# Shared helpers for scripts/quality-gate.sh, scripts/hooks/*.sh and .githooks/*.
# Source it; do not execute it. POSIX bash, no jq/node/python dependency.

# Print a message to stderr.
qg_err() { printf '%s\n' "$*" >&2; }

# Extract the FIRST JSON string value for key "$1" from JSON on stdin.
# Good enough for Claude Code hook payloads (tool_input.command, tool_input.file_path, cwd).
# Handles escaped quotes/backslashes inside the value; prints nothing if the key is absent.
json_string_field() {
  local key="$1"
  tr -d '\r\n' \
    | grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"([^\"\\\\]|\\\\.)*\"" \
    | head -n 1 \
    | sed -E "s/^\"${key}\"[[:space:]]*:[[:space:]]*\"//; s/\"\$//" \
    | sed -e 's/\\\\/\x01/g' -e 's/\\"/"/g' -e 's/\\[nrt]/ /g' -e 's/\\\//\//g' -e 's/\x01/\\/g'
}

# Convert a Windows path (C:\foo\bar) to forward slashes (C:/foo/bar). Idempotent on POSIX paths.
to_slashes() { printf '%s' "$1" | sed 's#\\#/#g'; }

# Resolve the git top-level directory for a directory/file path ($1, optional; default: cwd).
# Prints nothing and returns 1 if not inside a git work tree.
repo_root_for() {
  local p="${1:-.}"
  p="$(to_slashes "$p")"
  [ -d "$p" ] || p="$(dirname "$p")"
  while [ -n "$p" ] && [ ! -d "$p" ]; do p="$(dirname "$p")"; done
  git -C "${p:-.}" rev-parse --show-toplevel 2>/dev/null
}

# Ensure a JDK is usable: honour JAVA_HOME, then java on PATH, then the portable JDK
# under $USERPROFILE/.local/devtools/jdk/* (or $HOME/.local/devtools/jdk/*). Exports JAVA_HOME/PATH.
ensure_java() {
  if [ -n "${JAVA_HOME:-}" ]; then
    local jh
    jh="$(to_slashes "$JAVA_HOME")"
    if [ -x "$jh/bin/java" ] || [ -x "$jh/bin/java.exe" ]; then
      export JAVA_HOME="$jh"
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
  fi
  if command -v java >/dev/null 2>&1; then
    return 0
  fi
  local base candidate found=""
  for base in \
    "$(command -v cygpath >/dev/null 2>&1 && [ -n "${USERPROFILE:-}" ] && cygpath -u "$USERPROFILE")" \
    "$(to_slashes "${USERPROFILE:-}")" \
    "${HOME:-}"; do
    [ -n "$base" ] && [ -d "$base/.local/devtools/jdk" ] || continue
    for candidate in "$base"/.local/devtools/jdk/*; do
      if [ -x "$candidate/bin/java" ] || [ -x "$candidate/bin/java.exe" ]; then
        found="$candidate" # glob is sorted; keep the last (newest) one
      fi
    done
    [ -n "$found" ] && break
  done
  if [ -z "$found" ]; then
    qg_err "ERROR: no JDK found. Set JAVA_HOME, put java on PATH, or install a portable JDK 27"
    qg_err "       under \$USERPROFILE/.local/devtools/jdk/<jdk-dir>."
    return 1
  fi
  export JAVA_HOME="$found"
  export PATH="$JAVA_HOME/bin:$PATH"
}

# SHA of HEAD for the repo at $1 (default: cwd).
head_sha() { git -C "${1:-.}" rev-parse HEAD 2>/dev/null; }

# Returns 0 if target/review/REVIEW_OK in repo $1 contains exactly the current HEAD SHA.
# On failure prints the reason to stderr.
review_ok_matches_head() {
  local root="$1" marker head content
  marker="$root/target/review/REVIEW_OK"
  head="$(head_sha "$root")"
  if [ ! -f "$marker" ]; then
    qg_err "BLOCKED: reviewer approval missing ($marker not found)."
    qg_err "  Run the 'code-security-reviewer' subagent on this branch; it writes REVIEW_OK only when APPROVED."
    return 1
  fi
  content="$(tr -d '[:space:]' <"$marker")"
  if [ "$content" != "$head" ]; then
    qg_err "BLOCKED: REVIEW_OK is stale (approved ${content:-<empty>}, HEAD is $head)."
    qg_err "  New commits since the last review: re-run the 'code-security-reviewer' subagent."
    return 1
  fi
  return 0
}
