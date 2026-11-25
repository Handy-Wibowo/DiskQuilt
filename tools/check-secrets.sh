#!/usr/bin/env sh
#
# Blocks commits that contain credentials, or files that must never be published.
#
#   tools/check-secrets.sh            # scan staged files (what a commit would add)
#   tools/check-secrets.sh --all      # scan every tracked file
#   tools/check-secrets.sh FILE...    # scan explicit files
#
# Exit status: 0 clean, 1 findings, 2 not a git repository.
set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "check-secrets: not inside a git repository" >&2
    exit 2
}
cd "$ROOT" || exit 2

PATTERNS=$(mktemp) || exit 2
trap 'rm -f "$PATTERNS"' EXIT INT TERM

# High-confidence credential shapes only, so the hook does not cry wolf on ordinary code.
cat >"$PATTERNS" <<'EOF'
(kn|sk)-[A-Za-z0-9]{16,}
ghp_[A-Za-z0-9]{20,}
github_pat_[A-Za-z0-9_]{20,}
xox[baprs]-[A-Za-z0-9-]{10,}
AKIA[0-9A-Z]{16}
-----BEGIN [A-Z ]*PRIVATE KEY-----
Bearer [A-Za-z0-9._-]{24,}
EOF

# Paths that should never enter the repository at all.
BLOCKED_PATH='(^|/)(\.env|local\.properties|id_rsa|id_ed25519|credentials)$|\.(jks|keystore|p12|pfx|pem|key|apk|aab)$'

case "${1:---staged}" in
    --staged) LIST_CMD="git diff --cached --name-only --diff-filter=ACMR" ;;
    --all)    LIST_CMD="git ls-files" ;;
    *)        LIST_CMD="" ;;
esac

if [ -n "$LIST_CMD" ]; then
    FILES=$(eval "$LIST_CMD")
else
    FILES=$(printf '%s\n' "$@")
fi

[ -z "$FILES" ] && exit 0

FAILED=0
while IFS= read -r file; do
    [ -n "$file" ] || continue
    [ -f "$file" ] || continue

    # Placeholder files are expected to mention key names.
    case "$file" in
        *.env.example|*/.env.example) continue ;;
    esac

    if printf '%s\n' "$file" | grep -qE "$BLOCKED_PATH"; then
        printf 'check-secrets: blocked path  %s\n' "$file" >&2
        FAILED=1
        continue
    fi

    # -I skips binary files, so the Gradle wrapper jar is not misread as text.
    if grep -nIiE -f "$PATTERNS" -- "$file" >/dev/null 2>&1; then
        printf 'check-secrets: possible credential in %s\n' "$file" >&2
        grep -nIiE -f "$PATTERNS" -- "$file" | sed -E 's/^([0-9]+):.*/  line \1: <redacted>/' >&2
        FAILED=1
    fi
done <<EOF
$FILES
EOF

if [ "$FAILED" -ne 0 ]; then
    cat >&2 <<'MSG'

Commit blocked. Fix the findings above, then retry:
  - move secrets into .env (gitignored) and keep .env.example as placeholders
  - rotate any key that was written into a file
  - remove key stores, APKs and machine-specific files from the index:
      git rm --cached <path>
To bypass once (only when you are certain):  git commit --no-verify
MSG
    exit 1
fi

exit 0
