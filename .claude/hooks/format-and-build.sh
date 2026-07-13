#!/usr/bin/env bash
# PostToolUse hook on Edit|Write.
#
# Formats and compiles after a Java source edit, feeding any failure straight
# back to Claude as text so it is fixed immediately rather than discovered three
# edits later.
#
# Deliberately does NOT run the test suite: Testcontainers spins up a real
# PostgreSQL, which is far too slow to run on every keystroke. Tests are the
# gate-commit hook's job.
#
# Exit 0 always -- this hook advises, it does not block. (block-float-money is
# the one that blocks.)

set -uo pipefail

input="$(cat)"

file_path="$(printf '%s' "$input" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get("tool_input", {}).get("file_path", ""))
except Exception:
    print("")
' 2>/dev/null)"

# Only react to Java sources.
case "$file_path" in
    *.java) ;;
    *) exit 0 ;;
esac

project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"
[ -f "$project_dir/pom.xml" ] || exit 0

cd "$project_dir" || exit 0

# Format first, so the compile error line numbers match the formatted file.
mvn -q -B spotless:apply >/dev/null 2>&1

if ! mvn -q -B compile >/tmp/ledger-build.log 2>&1; then
    cat >&2 <<EOF
format-and-build: compilation FAILED after editing $file_path

$(grep -E '^\[ERROR\]' /tmp/ledger-build.log | head -20)

Full log: /tmp/ledger-build.log
EOF
fi

exit 0
