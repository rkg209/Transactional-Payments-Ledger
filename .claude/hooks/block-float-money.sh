#!/usr/bin/env bash
# PreToolUse hook on Edit|Write.
#
# Enforces two of the project's non-negotiable invariants mechanically, so that
# violating them is impossible rather than merely discouraged:
#
#   Invariant #1 — money is an integer count of minor units. Never float/double.
#   Invariant #2 — ledger_entries is append-only. Never UPDATE or DELETE.
#
# Exit 2 => block the tool call and feed the reason back to Claude.
# Exit 0 => allow.
#
# The hook reads the tool-call JSON on stdin.

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

# The text this edit would introduce: new_string (Edit) or content (Write).
new_text="$(printf '%s' "$input" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    ti = d.get("tool_input", {})
    parts = [ti.get("new_string", ""), ti.get("content", "")]
    for e in ti.get("edits", []) or []:
        parts.append(e.get("new_string", ""))
    print("\n".join(p for p in parts if p))
except Exception:
    print("")
' 2>/dev/null)"

[ -z "$new_text" ] && exit 0

# Only police source files. Docs, specs, and the progress report legitimately
# discuss floats and ledger mutation in prose -- that is the point of them.
case "$file_path" in
    *.java|*.sql|*.kt) ;;
    *) exit 0 ;;
esac

# ---------------------------------------------------------------------------
# Invariant #1 -- no floating-point money.
#
# Scoped to monetary identifiers rather than banning float outright: latency
# percentiles and benchmark timings are legitimately floating-point, and a hook
# that cries wolf gets disabled.
# ---------------------------------------------------------------------------
money_words='amount|balance|minor|money|cents|debit|credit|sum|total|fee|price|value'

if printf '%s' "$new_text" \
    | grep -inE "\b(float|double|Float|Double|BigDecimal)\b[^;=]*\b($money_words)" >/dev/null 2>&1; then
    cat >&2 <<EOF
BLOCKED by block-float-money hook -- invariant #1 violated.

A floating-point type is being introduced on what looks like a monetary field:

$(printf '%s' "$new_text" | grep -inE "\b(float|double|Float|Double|BigDecimal)\b[^;=]*\b($money_words)" | head -5)

Money in this project is an INTEGER count of minor units: use 'long' (or
BigInteger). Floating-point cannot represent 0.01 exactly, and the error
compounds across entries -- which is the exact class of bug this ledger exists
to make impossible.

If this identifier is genuinely not monetary (a latency, a ratio, a rate),
rename it so that is obvious to the next reader, and to this hook.
EOF
    exit 2
fi

# ---------------------------------------------------------------------------
# Invariant #2 -- ledger_entries is append-only.
#
# A database trigger enforces this at runtime too. This hook catches it earlier,
# at authoring time, with a better explanation than a Postgres exception.
# ---------------------------------------------------------------------------
if printf '%s' "$new_text" \
    | grep -inE '(UPDATE[[:space:]]+ledger_entries|DELETE[[:space:]]+FROM[[:space:]]+ledger_entries|\.update\(LEDGER_ENTRIES|\.delete(From)?\(LEDGER_ENTRIES|deleteFrom\(LEDGER_ENTRIES)' >/dev/null 2>&1; then
    cat >&2 <<EOF
BLOCKED by block-float-money hook -- invariant #2 violated.

This edit mutates ledger_entries:

$(printf '%s' "$new_text" | grep -inE '(UPDATE[[:space:]]+ledger_entries|DELETE[[:space:]]+FROM[[:space:]]+ledger_entries|\.update\(LEDGER_ENTRIES|\.delete(From)?\(LEDGER_ENTRIES|deleteFrom\(LEDGER_ENTRIES)' | head -5)

ledger_entries is APPEND-ONLY and IMMUTABLE. A posted entry is never updated or
deleted -- that is what makes the ledger auditable and what makes the zero-sum
invariant checkable at all.

To reverse a posting, INSERT a new compensating entry with the opposite
direction. The original entry stays. The audit trail gets longer, never shorter.

(The V1 migration installs a database trigger that will reject this at runtime
regardless, so this code could not work even if the hook allowed it.)
EOF
    exit 2
fi

exit 0
