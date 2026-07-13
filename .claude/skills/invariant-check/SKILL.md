---
name: invariant-check
description: Run the ledger invariant check — Σ(all entries) = 0 globally and per-account balance = Σ(its entries) — and report any drift. Use after any change touching transfers, balances, entries, or sagas.
---

# /invariant-check

Assert the two invariants that this entire project exists to uphold.

## The invariants

```sql
-- 1. Global zero-sum. Must return exactly 0.
SELECT SUM(CASE WHEN direction = 'DEBIT'  THEN -amount_minor
                WHEN direction = 'CREDIT' THEN  amount_minor END) AS global_sum
FROM ledger_entries;

-- 2. Per-account: materialized balance must equal the entry sum. Must return 0 rows.
SELECT le.account_id,
       SUM(CASE WHEN le.direction = 'DEBIT'  THEN -le.amount_minor
                WHEN le.direction = 'CREDIT' THEN  le.amount_minor END) AS entry_sum,
       a.balance AS materialized_balance
FROM ledger_entries le
JOIN accounts a ON a.id = le.account_id
GROUP BY le.account_id, a.balance
HAVING SUM(CASE WHEN le.direction = 'DEBIT'  THEN -le.amount_minor
                WHEN le.direction = 'CREDIT' THEN  le.amount_minor END) <> a.balance;
```

## How to run it

- **Against a running stack:** `POST /api/v1/reconciliation/run`, then `GET
  /api/v1/reconciliation/report`. Or run the SQL directly via the Postgres MCP server.
- **In tests:** the reconciliation assertions run at the end of every correctness test.
- Before SPEC 0005 exists, run the SQL directly.

## Report

State plainly:

- `global_sum` — and whether it is 0.
- Number of accounts checked, number drifted.
- For any drift: the account, the entry sum, the materialized balance, and the delta.

## If drift is found

**This is a genuine emergency, not a nit.** Money has been created or destroyed, or the balance
cache has diverged from the ledger. Stop and investigate before doing anything else.

Do **not** auto-repair. Do not "fix" the balance column to match. The drift is a *symptom*; the bug
that caused it is still there, and repairing the symptom destroys the evidence needed to find it.

Where to look, in order:
1. A balance write that bypassed `ConcurrencyStrategy` (so took no lock — a lost update).
2. A transfer that wrote entries but whose balance update was rolled back, or vice versa — i.e. the
   two are not actually in the same transaction.
3. A saga compensation that reversed the balance but not the entries.
4. Something wrote `accounts.balance` directly, outside the service layer.

**The entry sum is right. The balance column is wrong.** Entries are immutable and append-only; the
balance is a cache. Reason from the entries.
