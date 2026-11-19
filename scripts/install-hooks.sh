#!/usr/bin/env bash
# Activates the versioned git hooks in .githooks/ for this clone (run once).
set -eu
ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
cd "$ROOT"
git config core.hooksPath .githooks
chmod +x .githooks/* scripts/*.sh scripts/hooks/*.sh 2>/dev/null || true
echo "Git hooks activated: core.hooksPath=$(git config core.hooksPath)"
echo "pre-push now runs scripts/quality-gate.sh and requires target/review/REVIEW_OK == HEAD."
