#!/usr/bin/env bash
set -euo pipefail

# SPEC 0002 (DR-8) -- curl the ledger end to end: create two accounts, seed one with
# pre-existing capital, transfer between them, and show the total is conserved.
#
# Run against `docker compose up -d --build` (see README / Makefile `make up`).

BASE_URL="${BASE_URL:-http://localhost:8080}"
API_KEY="${API_KEY:-secret-dev-key-001}"
AUTH_HEADER="Authorization: ApiKey ${API_KEY}"
JSON_HEADER="Content-Type: application/json"

echo "==> Waiting for ${BASE_URL}/health ..."
until curl -sf "${BASE_URL}/health" > /dev/null; do
  sleep 1
done
echo "    up."

echo
echo "==> Creating account: alice"
ALICE=$(curl -sf -X POST "${BASE_URL}/api/v1/accounts" \
  -H "${AUTH_HEADER}" -H "${JSON_HEADER}" \
  -H "Idempotency-Key: demo-create-alice-$$" \
  -d '{"name":"alice","currency":"USD"}')
ALICE_ID=$(echo "$ALICE" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "    alice = ${ALICE_ID}"

echo "==> Creating account: bob"
BOB=$(curl -sf -X POST "${BASE_URL}/api/v1/accounts" \
  -H "${AUTH_HEADER}" -H "${JSON_HEADER}" \
  -H "Idempotency-Key: demo-create-bob-$$" \
  -d '{"name":"bob","currency":"USD"}')
BOB_ID=$(echo "$BOB" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "    bob   = ${BOB_ID}"

echo
echo "==> Seeding alice with pre-existing capital (10000 minor units)."
echo "    No mint/deposit endpoint exists in this API surface by design (a real one needs a"
echo "    counterpart account to stay balanced, and is its own spec) -- so, exactly like the"
echo "    integration tests' AbstractPostgresIT.seedInitialBalance, this represents capital that"
echo "    already existed before this ledger began, not money this system created. Seeded"
echo "    directly against Postgres, honestly, not smuggled through the HTTP API."
docker compose exec -T postgres psql -U ledger -d ledger -c \
  "UPDATE accounts SET balance = 10000 WHERE id = '${ALICE_ID}';" > /dev/null

echo
echo "==> Balances before transfer:"
curl -sf "${BASE_URL}/api/v1/accounts/${ALICE_ID}/balance" -H "${AUTH_HEADER}"; echo
curl -sf "${BASE_URL}/api/v1/accounts/${BOB_ID}/balance" -H "${AUTH_HEADER}"; echo

echo
echo "==> Transferring 2500 from alice to bob"
TRANSFER_KEY="demo-transfer-$$"
TRANSFER=$(curl -sf -X POST "${BASE_URL}/api/v1/transfers" \
  -H "${AUTH_HEADER}" -H "${JSON_HEADER}" \
  -H "Idempotency-Key: ${TRANSFER_KEY}" \
  -d "{\"fromAccountId\":\"${ALICE_ID}\",\"toAccountId\":\"${BOB_ID}\",\"amount\":2500,\"currency\":\"USD\"}")
TRANSFER_ID=$(echo "$TRANSFER" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo "    transfer = ${TRANSFER_ID}"
echo "$TRANSFER"

echo
echo "==> Balances after transfer:"
ALICE_BAL=$(curl -sf "${BASE_URL}/api/v1/accounts/${ALICE_ID}/balance" -H "${AUTH_HEADER}")
BOB_BAL=$(curl -sf "${BASE_URL}/api/v1/accounts/${BOB_ID}/balance" -H "${AUTH_HEADER}")
echo "$ALICE_BAL"
echo "$BOB_BAL"

ALICE_AMT=$(echo "$ALICE_BAL" | grep -o '"balance":[0-9-]*' | cut -d':' -f2)
BOB_AMT=$(echo "$BOB_BAL" | grep -o '"balance":[0-9-]*' | cut -d':' -f2)
TOTAL=$((ALICE_AMT + BOB_AMT))
echo
echo "==> Total across both accounts: ${TOTAL} (expected 10000 -- conserved)"
if [ "$TOTAL" -ne 10000 ]; then
  echo "    MISMATCH -- money was created or destroyed." >&2
  exit 1
fi

echo
echo "==> Fetching the transfer by id"
curl -sf "${BASE_URL}/api/v1/transfers/${TRANSFER_ID}" -H "${AUTH_HEADER}"; echo

echo
echo "=================================================================="
echo "  Idempotency (SPEC 0003) -- submitting the SAME request with the"
echo "  SAME Idempotency-Key a second time."
echo "=================================================================="
echo "  SPEC 0003 is implemented: the retry must replay the original 201"
echo "  response as a 200 with X-Idempotent-Replayed: true, and balances"
echo "  must NOT move a second time."
echo
REPLAY_HEADERS=$(mktemp)
REPLAY=$(curl -sf -D "$REPLAY_HEADERS" -X POST "${BASE_URL}/api/v1/transfers" \
  -H "${AUTH_HEADER}" -H "${JSON_HEADER}" \
  -H "Idempotency-Key: ${TRANSFER_KEY}" \
  -d "{\"fromAccountId\":\"${ALICE_ID}\",\"toAccountId\":\"${BOB_ID}\",\"amount\":2500,\"currency\":\"USD\"}")
echo "$REPLAY"
if ! grep -qi '^X-Idempotent-Replayed: *true' "$REPLAY_HEADERS"; then
  echo "    MISMATCH -- expected X-Idempotent-Replayed: true on the replay." >&2
  rm -f "$REPLAY_HEADERS"
  exit 1
fi
rm -f "$REPLAY_HEADERS"
ALICE_BAL_2=$(curl -sf "${BASE_URL}/api/v1/accounts/${ALICE_ID}/balance" -H "${AUTH_HEADER}")
BOB_BAL_2=$(curl -sf "${BASE_URL}/api/v1/accounts/${BOB_ID}/balance" -H "${AUTH_HEADER}")
echo "    alice after replay: ${ALICE_BAL_2}"
echo "    bob after replay:   ${BOB_BAL_2}"
ALICE_AMT_2=$(echo "$ALICE_BAL_2" | grep -o '"balance":[0-9-]*' | cut -d':' -f2)
BOB_AMT_2=$(echo "$BOB_BAL_2" | grep -o '"balance":[0-9-]*' | cut -d':' -f2)
if [ "$ALICE_AMT_2" -ne "$ALICE_AMT" ] || [ "$BOB_AMT_2" -ne "$BOB_AMT" ]; then
  echo "    MISMATCH -- balances moved on replay; the transfer was double-applied." >&2
  exit 1
fi
echo "    balances unchanged -- the replay was a true no-op, as SPEC 0003 requires."

echo
echo "==> Ledger entries for this transfer (must be exactly 2 -- a balanced debit/credit pair,"
echo "    not 4, proving the replay above posted nothing new):"
ENTRY_COUNT=$(docker compose exec -T postgres psql -U ledger -d ledger -tA -c \
  "SELECT count(*) FROM ledger_entries WHERE transfer_id = '${TRANSFER_ID}';")
echo "    entries for transfer ${TRANSFER_ID}: ${ENTRY_COUNT}"
if [ "$ENTRY_COUNT" -ne 2 ]; then
  echo "    MISMATCH -- expected exactly 2 ledger entries for one applied transfer." >&2
  exit 1
fi

echo
echo "==> Sigma(ledger_entries) across the WHOLE ledger, read straight from Postgres:"
GLOBAL_SUM=$(docker compose exec -T postgres psql -U ledger -d ledger -tA -c \
  "SELECT coalesce(sum(CASE WHEN direction = 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) FROM ledger_entries;")
echo "    Sigma(entries) = ${GLOBAL_SUM}"
if [ "$GLOBAL_SUM" -ne 0 ]; then
  echo "    MISMATCH -- the ledger's global invariant does not hold." >&2
  exit 1
fi

echo
echo "=================================================================="
echo "  Summary -- what this run just proved:"
echo "=================================================================="
echo "    - One transfer of 2500 applied exactly once (${ENTRY_COUNT} ledger entries, not 4)."
echo "    - The total across alice+bob stayed at 10000: no money created or destroyed."
echo "    - Replaying the SAME Idempotency-Key was a true no-op (X-Idempotent-Replayed: true,"
echo "      balances unchanged) -- no double-charge."
echo "    - Sigma(ledger_entries) = ${GLOBAL_SUM} across the entire ledger, not just this transfer."

echo
echo "==> demo.sh complete."
