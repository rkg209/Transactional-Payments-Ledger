# ADR 0007 — Reconciliation is report-only; the entry sum is authoritative

Date: 2026-07-21
Status: accepted
Deciders: Project owner
Relates to: SPEC 0005, FR-11, FR-24, FR-25, FR-26, NFR-3, NFR-20

## Context

CLAUDE.md invariant 5 already states the rule this ADR exists to defend at runtime: "A balance
equals the sum of its account's entries. `accounts.balance` is a cache, not the source of truth. If
they ever disagree, the entry sum wins." Every prior spec asserted this only inside its own tests.
SPEC 0005 turns it into a standing job (`ReconciliationService.runCheck()`) that re-derives both
invariants from the ledger on a schedule, persists the verdict, and screams when it fails. Four
decisions in that design need to be on the record, because each one is the kind of thing a later
change could quietly undo without realizing it was load-bearing.

## Decision

**1. Report-only. No auto-repair, anywhere — not a config flag, not a commented-out method.**
Drift is a *symptom*: some write path bypassed `ConcurrencyStrategy` (a lost update that took no
lock), or wrote entries and balance in different transactions, or a saga compensation reversed one
side but not the other. Silently overwriting `accounts.balance` to match the entry sum erases the
evidence needed to find that bug — the next occurrence looks identical to the last one, because the
symptom was deleted each time instead of investigated. `ReconciliationRepository` structurally
cannot write to `accounts` or `ledger_entries` (it exposes exactly one write, `insertReport`, into
`reconciliation_reports`), and ArchUnit's `reconciliation_must_not_depend_on_forbidden_modules` rule
means no future addition to the package can route around that by depending on `transfer` or
`concurrency` to reach a write path either.

**2. The entry sum is authoritative; the balance column is the cache being checked.** This is not
symmetric. `ledger_entries` is append-only and trigger-enforced immutable (invariant 2); `balance`
is a denormalized column updated by ordinary `UPDATE` statements for read performance. When they
disagree, only one of them could be wrong by construction, and it is never the one that cannot be
mutated after the fact.

**3. The per-account check is a `LEFT JOIN` over every account, not an `INNER JOIN` over accounts
that have entries.** `planning/03-system-design.md` §3.3 and the `/invariant-check` skill's query 2
both used `INNER JOIN ... ledger_entries`, which makes an account holding a nonzero balance with
zero entries invisible to the check — exactly the shape money-from-nowhere would take. SPEC 0005's
text says "for every account"; the stricter reading is the one that actually catches that case, so
`ReconciliationRepository.findDriftedAccounts()` left-joins and treats a missing entry-sum row as 0
(`DriftDetectionIT`'s second case exists specifically to prove the `LEFT JOIN` catches what an
`INNER JOIN` would miss).

**4. Both sides of a per-account comparison are read in one SQL statement.** Under READ COMMITTED,
each *statement* gets its own snapshot, not each transaction. Reading `accounts.balance` in one
query and the entry sum in a second query — even inside the same read-only transaction — leaves a
window where a transfer commits between the two statements and one side sees it while the other
does not, manufacturing phantom drift that never existed at any real point in time. Fetching both
in a single `SELECT` (`accounts LEFT JOIN` a grouped subquery over `ledger_entries`) closes that
window by construction: one statement is one snapshot, so "before" and "after" cannot straddle it.

**5. Genesis-account funding for the accounts *downstream* of it; genesis itself stays a known,
constant, external seed.** The plan going in was a genesis account with a deeply negative
`min_balance`, so it alone could fund others while every account — genesis included — still
satisfied `balance == Σentries` exactly. Implementation found that impossible: `accounts_min_balance_gte_zero`
(`CHECK (min_balance >= 0)`) is unconditional, for every row, at both INSERT and UPDATE — no account
in this schema can ever go negative, which means no account can donate the *first* unit of capital
to another without having held it already. That is not a bug to route around; it is the schema
correctly refusing to let any single account become an unbounded money source. Some account,
somewhere, must still start from an external, entry-less seed — the same concept `seedInitialBalance`
already documents. Genesis is that account: seeded once with a known constant
(`GENESIS_STARTING_CAPITAL`), it funds every other test account through real `TransferService`
transfers, so `balance == Σentries` holds exactly for every account *except* genesis. Genesis's own
drift is fixed at exactly the seed amount regardless of how much is drawn from it — `balance` and
`entrySum` move together, unit for unit, for every transfer out, leaving the gap constant — so
reconciliation tests assert on it explicitly (one known, named drifted account) rather than
excluding it from the query the way `seedInitialBalance`-seeded accounts are excluded from test
assertions elsewhere. The production query itself still carves out nothing: the exclusion, such as
it is, lives only in what a given test expects to see, identical in kind to how every other IT in
this codebase already treats a seeded account.

## Consequences

**Positive:** a drift report is trustworthy evidence of a real bug, never noise from the checker's
own timing or its own test fixtures; the checker's structural inability to write to `accounts` or
`ledger_entries` means "did reconciliation touch the data" is not a question anyone has to ask
during an incident.

**Negative / accepted costs:** drift, once detected, requires a human to actually investigate and
fix the root cause — there is no fast path that makes an alert go away by itself. The per-account
query's index-only-scan requirement (`idx_ledger_entries_reconciliation` covering `account_id`,
`direction`, `amount_minor`) is now load-bearing for NFR-20's latency target; it depends on a
reasonably fresh visibility map (post-`VACUUM`/`ANALYZE`), which is why `ReconciliationPerformanceIT`
runs `ANALYZE` before asserting the plan rather than trusting the planner on a freshly-written table.

**What would change our mind:** if a real incident ever showed drift recurring faster than a human
could reasonably triage it — at which point the right fix is almost certainly closing the write-path
gap that caused it, not adding a repair path to this service.
