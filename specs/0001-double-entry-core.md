# SPEC 0001 — Double-entry core

Status: verified
Depends on: 0000
Requirements: FR-1, FR-2, FR-3, FR-4, FR-5, FR-6, NFR-2, NFR-3, NFR-14, NFR-18, CON-4, CON-5

## Goal

Post a balanced transfer atomically and derive balances from entries. This is the foundation every
later spec rests on: if money can be created or destroyed here, no amount of idempotency or locking
above it can save the system. No HTTP, no concurrency handling — just the core accounting operation,
correct in a single ACID transaction.

## In scope

- `TransferService.execute()` — in **one** ACID transaction: insert the `transfers` row, insert
  exactly one DEBIT and one CREDIT `ledger_entries` row of equal `amount_minor`, update both
  account balances, mark the transfer `COMPLETED`.
- Rejection of any unbalanced posting, with no partial state persisted.
- Minimum-balance enforcement (no overdraft): reject before any entry is written.
- Balance derivation: `balance = Σ(entries)` with sign applied by direction.
- `AccountService.createAccount()` / `getAccount()`.
- jOOQ repositories: `AccountRepository`, `LedgerEntryRepository`, `TransferRepository`.
- ArchUnit rules enforcing the module dependency contract and "no UPDATE/DELETE on ledger_entries".

## Out of scope

- The HTTP layer. (SPEC 0002)
- Idempotency. (SPEC 0003)
- Concurrency control — for now, use a plain read-then-write. It is *knowingly* racy and SPEC 0004
  fixes it. Do not paper over this with an ad-hoc lock; the whole point of 0004 is to make the
  locking strategy an explicit, benchmarked choice rather than an accident.

## Design notes

The zero-sum invariant is enforced **by construction**, not by checking afterwards: `execute()` has
exactly one code path that writes entries, and it always writes the DEBIT and the CREDIT together
in the same transaction. There is no API by which a caller can post a single unbalanced entry.

Money is `long` minor units throughout — see invariant #1. The `block-float-money` hook will reject
any diff that introduces a floating-point monetary field, so this is enforced, not merely intended.

The insufficient-funds check happens **before** any write, so rejection needs no rollback of
already-written entries. The database `CHECK (balance >= min_balance)` constraint is the backstop,
not the primary guard.

## Acceptance criteria (the measurable "done")

- [x] A balanced transfer persists: 2 entries, summing to zero, and both balances move by exactly
      `amount_minor` in opposite directions.
- [x] Money is conserved: the sum of both account balances is unchanged by any transfer.
- [x] `Σ(all ledger_entries) = 0` after every committed transfer.
- [x] An unbalanced posting is rejected and **nothing** is persisted (no orphan transfer row).
- [x] A transfer that would push the source below `min_balance` is rejected with
      `InsufficientFundsException`, and no entries are written.
- [x] `balance` (materialized) equals `Σ(entries)` for every account, asserted after every test.
- [x] ArchUnit: no code outside `LedgerEntryRepository` touches `ledger_entries`; no `UPDATE`/
      `DELETE` against it anywhere.

## Test plan

- `DoubleEntryCoreIT` — happy path, conservation of money, Σ = 0.
- `UnbalancedPostingIT` — rejection leaves zero rows behind.
- `InsufficientFundsIT` — overdraft rejected atomically; balance untouched.
- `LedgerImmutabilityIT` — the DB trigger rejects UPDATE/DELETE.
- `ArchitectureFitnessTest` — module boundaries and the ledger-entry write rule.
- Property test: N random transfer schedules over M accounts → conservation and non-negativity
  never violated.
