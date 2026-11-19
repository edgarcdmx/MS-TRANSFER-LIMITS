#!/usr/bin/env bash
# Claude Code PostToolUse hook (matcher: Edit|Write|MultiEdit).
# If the edited file is a .java file, format it with Spotless (google-java-format) in place.
# Never fails the tool: always exits 0 and only prints a notice.
set -u

HOOK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../lib/common.sh
. "$HOOK_DIR/../lib/common.sh"

INPUT="$(cat)"
FILE="$(printf '%s' "$INPUT" | json_string_field file_path)"
case "$FILE" in
  *.java) ;;
  *) exit 0 ;;
esac

PATH_FWD="$(to_slashes "$FILE")"
ROOT="$(repo_root_for "$PATH_FWD")"
if [ -z "$ROOT" ] || [ ! -f "$ROOT/mvnw" ]; then
  echo "[format-java] skipped: $FILE is not inside a Maven-wrapper repository."
  exit 0
fi

# Repo-relative path, then a regex that matches it with either separator (Spotless matches
# -DspotlessFiles regexes against the absolute OS path, which uses '\' on Windows).
REL="$PATH_FWD"
root_lc="$(printf '%s' "$ROOT" | tr '[:upper:]' '[:lower:]')"
path_lc="$(printf '%s' "$PATH_FWD" | tr '[:upper:]' '[:lower:]')"
case "$path_lc" in
  "$root_lc"/*) REL="${PATH_FWD:${#ROOT}+1}" ;;
  *) REL="${PATH_FWD#/}" ;;
esac
# Escape regex metacharacters, then turn '/' into a separator class.
ESCAPED="$(printf '%s' "$REL" | sed -e 's/[][\.^$*+?(){}|]/\\&/g' -e 's#/#[\\\\/]#g')"
REGEX=".*[\\\\/]${ESCAPED}"

if ! ensure_java 2>/dev/null; then
  echo "[format-java] skipped: no JDK found (set JAVA_HOME). Run './mvnw spotless:apply' later."
  exit 0
fi

if (cd "$ROOT" && ./mvnw -q -B -ntp spotless:apply "-DspotlessFiles=$REGEX") >/dev/null 2>&1; then
  echo "[format-java] formatted $REL with google-java-format (spotless:apply)."
else
  echo "[format-java] WARNING: spotless:apply failed for $REL (syntax error?). Run './mvnw spotless:apply' to see why."
fi
exit 0
