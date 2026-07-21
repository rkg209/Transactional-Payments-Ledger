# SPEC 0005 — Invariant & reconciliation

Status: implemented
Depends on: 0004
Requirements: FR-11, FR-24, FR-25, FR-26, NFR-3, NFR-20

## Goal

Continuously *prove* the books balance, rather than assuming they do. This is the project's safety
net: every other spec's correctness claim is ultimately checked here.

## In scope

- `ReconciliationService`, `@Scheduled` (default 60s), asserting:
  - **Global:** `Σ(signed amount_minor) across all ledger_entries = 0`.
  - **Per-account:** `accounts.balance = Σ(that account's entries)` for every account.
- `GET /api/v1/reconciliation/report` and `POST /api/v1/reconciliation/run`.
- Results persisted to `reconciliation_reports`.
- Drift detection: structured ERROR log + Micrometer counter + flagged in the report.
- Deliberate drift injection (test-profile only) so we can prove the detector actually detects.

## Out of scope

- **Auto-repair.** Report only. A ledger that silently "fixes" itself destroys the audit trail and
  hides the bug that caused the drift. A human investigates.

## Design notes

If `accounts.balance` and `Σ(entries)` ever disagree, **the entry sum is right and the balance is
wrong** — entries are immutable and append-only, the balance column is a cache. The report says so;
it does not average them or trust the cache.

The per-account query is served by the covering index `idx_ledger_entries_reconciliation
(account_id, direction, amount_minor)`, so the SUM is an index-only scan and does not touch the
heap. On a large `ledger_entries` table this is the difference between a viable background job and
one that hammers the database every minute.

**Drift injection is the point of this spec, not a footnote.** A detector that has never fired is
not a detector; it is an assumption. FR-26 exists because "the invariant held" is only meaningful
if we have watched the check fail when it should.

## Acceptance criteria (the measurable "done")

- [ ] The invariant holds after every test run in the suite.
- [ ] An **intentionally injected** drift is detected, reported, logged, and counted in metrics.
- [ ] A clean ledger reports `driftDetected: false`, `globalSum: 0`, `accountsDrifted: 0`.
- [ ] `GET /reconciliation/report` returns the latest persisted report.
- [ ] The reconciliation query uses an index-only scan (verify with `EXPLAIN`).
- [ ] Drift is **never** auto-repaired.

## Test plan

- `ReconciliationIT` — clean ledger → no drift; report persisted.
- `DriftDetectionIT` — inject drift (bypass the service, write directly to `accounts.balance`),
  assert it is detected, reported, and the metric incremented. **The load-bearing test.**
- `ReconciliationPerformanceIT` — `EXPLAIN` confirms the index-only scan.
