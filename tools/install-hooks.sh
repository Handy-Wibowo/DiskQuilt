#!/usr/bin/env sh
# One-time setup per clone: points Git at the committed hooks and makes them executable.
# core.hooksPath is local configuration, so a fresh clone must run this once.
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "install-hooks: not inside a git repository" >&2
    exit 2
}
cd "$ROOT"

git config core.hooksPath .githooks
chmod +x .githooks/pre-commit tools/check-secrets.sh 2>/dev/null || true

echo "hooks installed: core.hooksPath=$(git config core.hooksPath)"
echo "verify with: tools/check-secrets.sh --all"
