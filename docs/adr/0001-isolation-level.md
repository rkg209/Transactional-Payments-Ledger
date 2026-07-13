# ADR 0001 — Isolation level for the transfer path

Date: 2026-07-14
Status: accepted
Deciders: Project owner
Relates to: SPEC 0004, FR-22, NFR-14

## Context

Every transfer performs a read-modify-write on two account rows: read the balance, check it against
`min_balance`, write the new balance. Under concurrency this is the textbook setting for a **lost
update** — two transactions read balance 100, each subtracts 30, each writes 70, and 30 units of
money are invented from nothing.

PostgreSQL offers three usable isolation levels, and the choice interacts with the locking strategy.
The two decisions are really one decision, and FR-22 requires that we name the specific anomalies
our choice prevents — and the ones it does not.

The anomalies in play:

| Anomaly | READ COMMITTED | REPEATABLE READ | SERIALIZABLE |
|---|---|---|---|
| Dirty read | prevented | prevented | prevented |
| Non-repeatable read | **allowed** | prevented | prevented |
| Phantom read | **allowed** | prevented (in PG) | prevented |
| **Lost update** | **allowed** | detected (serialization error) | detected |
| **Write skew** | **allowed** | **allowed** | prevented |

The critical row is **lost update**: READ COMMITTED does not prevent it. That is the entire reason
a concurrency-control layer exists in this system.

## Options considered

### Option A — SERIALIZABLE alone, no explicit locking
- **Pros:** Prevents every anomaly including write skew. Correct by default; the application does
  not have to reason about locks at all.
- **Cons:** Pays for guarantees this access pattern does not need. Serialization failures must
  still be retried, so the application does not actually escape retry logic — it just moves it.
  Highest throughput cost, and PostgreSQL's SSI predicate-locking overhead grows with contention,
  which is exactly the regime this project targets.

### Option B — REPEATABLE READ, no explicit locking
- **Pros:** Detects lost updates and raises a serialization error.
- **Cons:** Still requires retry handling, so the complexity is not avoided. Allows write skew.
  And it makes the locking behavior *implicit* — the SQL at the call site does not show what is
  being locked, which is precisely the transparency this project is built to demonstrate.

### Option C — READ COMMITTED + explicit locking (chosen)
- **Pros:** The locking is **visible at the call site**: either `SELECT … FOR UPDATE` (pessimistic)
  or `UPDATE … WHERE version = ?` (optimistic). For this access pattern — a read-then-write on a
  *known, finite row set* — explicit locking gives the same lost-update protection as REPEATABLE
  READ, without the snapshot-isolation overhead. Lowest cost, highest clarity, and it makes the
  optimistic-vs-pessimistic comparison a clean A/B test (SPEC 0008).
- **Cons:** Correctness now depends on the application *actually taking the lock*. A code path that
  forgets to go through `ConcurrencyStrategy` is silently racy — the database will not save us.
  This is a real risk and we accept it with mitigations.

## Decision

**READ COMMITTED for the transfer path, combined with mandatory explicit locking** via the
`ConcurrencyStrategy` layer — either `SELECT … FOR UPDATE` (pessimistic) or a version compare-and-set
(optimistic).

`SERIALIZABLE` remains available via `ISOLATION_LEVEL=serializable` so SPEC 0008 can measure exactly
what the stronger guarantee costs. Naming that price is itself a deliverable.

## Consequences

**Positive:**
- Lost updates are prevented, by explicit lock, on the only path that writes balances.
- The SQL that runs and the locks it takes are readable at the call site — no ORM, no implicit
  snapshot magic.
- Enables the optimistic-vs-pessimistic benchmark as a controlled comparison, since isolation is
  held constant and only the locking strategy varies.

**Negative / accepted costs:**
- **Write skew is not prevented.** This is safe *only* because no transfer decision reads one row
  and writes a different one — the balance check and the balance write are on the same rows, which
  the transaction locks. **If a future feature ever reads account X to decide a write to account Y
  (e.g. a cross-account credit limit), this reasoning breaks and that path needs SERIALIZABLE.**
  This is the sharpest edge in the whole design and is written down so it is not rediscovered the
  hard way.
- Correctness depends on discipline: every balance write must go through `ConcurrencyStrategy`.
  Mitigated by ArchUnit rules and the `concurrency-reviewer` subagent, but the risk is real.
- Lock ordering (ascending UUID) must be respected everywhere, or bidirectional transfers deadlock.

**What would change our mind:**
- A feature that introduces a genuine write-skew hazard (a constraint spanning rows that are not
  all locked). Then the affected path moves to SERIALIZABLE.
- SPEC 0008 measuring the SERIALIZABLE cost as negligible under our contention profile. If the
  guarantee turns out to be nearly free, buying it unconditionally is the better trade — simpler
  reasoning beats a small throughput win.
