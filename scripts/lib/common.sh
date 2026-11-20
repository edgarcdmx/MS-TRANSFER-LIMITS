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

# ---------------------------------------------------------------------------------------------
# git push detection for the Claude Code PreToolUse hook (Bash and PowerShell tools).
# These are GUARDRAILS against accidental or casual bypasses, not a security boundary: a shell
# command can always be obfuscated (variables, eval, encoded strings, scripts written to disk).
# Real enforcement is GitHub branch protection + required CI checks on the remote.
# ---------------------------------------------------------------------------------------------

# Print normalized variants of a command ($1), one per line (pure bash, no forks):
#   1. CR/LF/TAB -> space; backslash or backtick line continuations joined; quotes (' " `)
#      removed; other backslashes turned into '/' (C:\Git\cmd\git.exe -> C:/Git/cmd/git.exe);
#   2. same, but every remaining backslash deleted (shell escapes: g\it -> git).
# A match in any variant counts.
normalize_command_variants() {
  local base="${1//[$'\r\n\t']/ }"
  base="${base//\\ / }"
  base="${base//\` / }"
  base="${base//[\"\'\`]/}"
  printf '%s\n' "${base//\\//}" "${base//\\/}"
}

# git (or git.exe, optionally with a path prefix), then global options (-C dir, -c k=v,
# --git-dir x, --no-pager, -P ...), then the push subcommand.
GIT_PUSH_RE='(^|[^[:alnum:]_.-])git(\.exe)?([[:space:]]+-[Cc][[:space:]]+[^[:space:]]+|[[:space:]]+--(git-dir|work-tree|namespace|exec-path|config-env|super-prefix)[[:space:]]+[^[:space:]]+|[[:space:]]+--?[[:alnum:]-]+(=[^[:space:]]*)?)*[[:space:]]+push([[:space:]]|$|[;&|)<>])'
# "push" as a standalone word (not pushy, push-later, git-push-x).
PUSH_WORD_RE='(^|[^[:alnum:]_.-])push([^[:alnum:]_-]|$)'
NO_VERIFY_RE='--no-verify'
HOOKS_PATH_RE='core\.hookspath'
ALIAS_RE='(^|[^[:alnum:]_])alias\.'
EVAL_RE='(^|[^[:alnum:]_-])(eval|invoke-expression|iex)([^[:alnum:]_-]|$)'
EXPANSION_PUSH_RE='\$[({]?[[:alnum:]_:]*[)}]?[^;&|]*[[:space:]]push([^[:alnum:]_-]|$)'

# Returns 0 if any normalized variant in $1 (newline separated, already lower-cased) matches $2.
_any_variant_matches() {
  local line
  while IFS= read -r line; do
    [[ $line =~ $2 ]] && return 0
  done <<<"$1"
  return 1
}

# Classify a raw command string ($1). Prints one of:
#   none                - not a push, nothing to do
#   push                - a git push: require the approval and run the gate
#   forbidden:<reason>  - a push form that evades the native hooks or cannot be analysed
classify_git_command() {
  local variants
  variants="$(normalize_command_variants "$1")"
  # git and PowerShell are case-insensitive on Windows (GIT.EXE works): compare lower-cased.
  variants="${variants,,}"
  if _any_variant_matches "$variants" "$NO_VERIFY_RE"; then
    echo "forbidden:--no-verify skips the git hooks"
  elif ! _any_variant_matches "$variants" "$PUSH_WORD_RE"; then
    echo "none"
  elif _any_variant_matches "$variants" "$HOOKS_PATH_RE"; then
    echo "forbidden:overriding core.hooksPath together with push disables the native pre-push hook"
  elif _any_variant_matches "$variants" "$ALIAS_RE"; then
    echo "forbidden:git aliases that wrap push hide it from the gate"
  elif _any_variant_matches "$variants" "$EVAL_RE"; then
    echo "forbidden:eval/Invoke-Expression together with push cannot be analysed"
  elif _any_variant_matches "$variants" "$EXPANSION_PUSH_RE"; then
    echo "forbidden:variable or command expansion followed by push cannot be analysed"
  elif _any_variant_matches "$variants" "$GIT_PUSH_RE"; then
    echo "push"
  else
    echo "none"
  fi
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

# Returns 0 if target/review/REVIEW_OK in repo $1 contains exactly the SHA $2.
# On failure prints the reason to stderr.
review_ok_matches_sha() {
  local root="$1" sha="$2" marker content
  marker="$root/target/review/REVIEW_OK"
  if [ ! -f "$marker" ]; then
    qg_err "BLOCKED: reviewer approval missing ($marker not found)."
    qg_err "  Run the 'code-security-reviewer' subagent on this branch; it writes REVIEW_OK only when APPROVED."
    return 1
  fi
  content="$(tr -d '[:space:]' <"$marker")"
  if [ -z "$sha" ] || [ "$content" != "$sha" ]; then
    qg_err "BLOCKED: REVIEW_OK approves ${content:-<empty>}, but ${sha:-<unknown>} is being pushed."
    qg_err "  Only the reviewed commit can be pushed: re-run the 'code-security-reviewer' subagent."
    return 1
  fi
  return 0
}

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
