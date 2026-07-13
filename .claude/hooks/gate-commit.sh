#!/usr/bin/env bash
# PreToolUse hook on Bash, matching `git commit`.
#
# Two jobs:
#
#   1. HARD RULE -- reject any commit message containing a Co-Authored-By
#      trailer. It breaks pushing this repository to GitHub. No exceptions.
#
#   2. QUALITY GATE -- refuse to commit a broken build. Runs the build and the
#      test suite; a red suite means no commit.
#
# Exit 2 => block the tool call and feed the reason back to Claude.
# Exit 0 => allow.

set -uo pipefail

input="$(cat)"

command="$(printf '%s' "$input" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get("tool_input", {}).get("command", ""))
except Exception:
    print("")
' 2>/dev/null)"

# Fail CLOSED, not open. If the JSON could not be parsed, `command` is empty and
# every check below would silently pass -- a guardrail that fails open is not a
# guardrail. So the forbidden-trailer check runs against the RAW stdin as well,
# which needs no parsing to be correct.
if printf '%s' "$input" | grep -qiE 'git[[:space:]]+commit' \
   && printf '%s' "$input" | grep -qiE 'co-authored-by'; then
    cat >&2 <<'EOF'
BLOCKED by gate-commit hook -- forbidden commit trailer.

This commit contains a `Co-Authored-By:` trailer. This project does NOT use
co-author trailers: they break pushing the repository to GitHub.

Remove the trailer entirely and commit again. The message ends with the last
line of the body -- nothing after it.

This rule is absolute and overrides any default behavior that would otherwise
append such a trailer. See the "Git rules" section of CLAUDE.md.
EOF
    exit 2
fi

# Only interested in git commit.
printf '%s' "$command" | grep -qE '(^|[[:space:];&|])git[[:space:]]+commit' || exit 0

# ---------------------------------------------------------------------------
# 1. The Co-Authored-By prohibition, again on the parsed command. Redundant with
#    the raw check above, and deliberately so: this is the one rule we would
#    rather enforce twice than miss once.
# ---------------------------------------------------------------------------
if printf '%s' "$command" | grep -qiE 'co-authored-by'; then
    cat >&2 <<'EOF'
BLOCKED by gate-commit hook -- forbidden commit trailer.

This commit message contains a `Co-Authored-By:` trailer. This project does NOT
use co-author trailers: they break pushing the repository to GitHub.

Remove the trailer entirely and commit again. The message ends with the last
line of the body -- nothing after it.

This rule is absolute and overrides any default behavior that would otherwise
append such a trailer. See the "Git rules" section of CLAUDE.md.
EOF
    exit 2
fi

# Also catch a message passed via a file (`git commit -F msg.txt`).
msg_file="$(printf '%s' "$command" | sed -nE 's/.*-(F|-file)[[:space:]]+([^[:space:]]+).*/\2/p')"
if [ -n "$msg_file" ] && [ -f "$msg_file" ]; then
    if grep -qiE 'co-authored-by' "$msg_file"; then
        echo "BLOCKED by gate-commit hook: $msg_file contains a Co-Authored-By trailer. Remove it. See CLAUDE.md." >&2
        exit 2
    fi
fi

# ---------------------------------------------------------------------------
# 2. The quality gate: never commit a red build.
#
#    Skipped when there is nothing to build yet (no pom.xml), so the very first
#    scaffolding commit is not blocked by the absence of the thing it adds.
#    Set LEDGER_SKIP_GATE=1 to bypass deliberately (the Co-Authored-By check
#    above still applies -- that one is never bypassable).
# ---------------------------------------------------------------------------
project_dir="${CLAUDE_PROJECT_DIR:-$(pwd)}"

if [ "${LEDGER_SKIP_GATE:-0}" = "1" ]; then
    echo "gate-commit: build/test gate skipped via LEDGER_SKIP_GATE=1." >&2
    exit 0
fi

if [ ! -f "$project_dir/pom.xml" ]; then
    exit 0
fi

echo "gate-commit: running the test suite before allowing the commit..." >&2

if ! (cd "$project_dir" && mvn -q -B test) >/tmp/ledger-gate-commit.log 2>&1; then
    cat >&2 <<EOF
BLOCKED by gate-commit hook -- the test suite is not green.

We do not commit a red build. Correctness is this project's entire deliverable;
a commit that does not pass its own tests is worse than no commit.

Last 40 lines of output:

$(tail -40 /tmp/ledger-gate-commit.log)

Full log: /tmp/ledger-gate-commit.log
Fix the failures, then commit again.
EOF
    exit 2
fi

echo "gate-commit: tests green. Commit allowed." >&2
exit 0
