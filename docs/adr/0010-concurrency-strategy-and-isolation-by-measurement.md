# ADR 0010 — Concurrency strategy and isolation level, decided by measurement

Date: 2026-07-22
Status: accepted
Deciders: Project owner
Relates to: SPEC 0008, FR-34, FR-35, NFR-6, NFR-7, NFR-8, ADR 0001, ADR 0006

## Context

ADR 0001 picked READ COMMITTED + explicit locking over SERIALIZABLE, on the grounds that this
access pattern (read-then-write on a known, finite row set) does not need write-skew protection,
but left the exact cost of SERIALIZABLE unmeasured. ADR 0006 built both `OptimisticStrategy` and
`PessimisticStrategy` behind `ConcurrencyStrategy`, deliberately refused to pick a production
default, and named the expected shape: optimistic wins at low contention (no lock acquisition
cost, conflicts are rare), pessimistic wins at high contention (retry storms make every wasted
optimistic attempt worse), with a crossover somewhere in between. `application.yml:24`'s Hikari
pool size was deferred the same way. SPEC 0008 exists to replace that expectation with data.

`TransferBenchmark` (`org.ledger.bench`, JMH `SingleShotTime`) drove the full 2×2 matrix —
`{optimistic, pessimistic} × {read_committed, serializable}` — across contention levels {1, 2, 4,
8, 16, 32, 64} threads per hot account, timing `TransferService.execute(...)` directly against a
real Testcontainers Postgres. Each cell aggregates the median of 3 measurement iterations (`make
bench`, full sweep, ~7:46 total on the author's laptop). Full results: `docs/bench/results.md`,
`docs/bench/results.csv`, `docs/bench/latency.svg`.

## What the data showed

**The expected crossover under READ COMMITTED did not appear.** Pessimistic outperformed
optimistic at *every* contention level measured, including contention=1 (370 vs 337 transfers/sec)
where lock-acquisition cost should be irrelevant — there is nothing to contend for. The gap widens
sharply as contention rises: at contention=4, pessimistic does 843 transfers/sec against
optimistic's 269; at contention=64, pessimistic holds 643 against optimistic's 233, and optimistic
has 836 retry-exhausted failures in that cell against pessimistic's zero. `crossoverContention`
(`BenchmarkReport`) reports the crossover as contention level 1 under `read_committed` — pessimistic
never loses, so there is no window where optimism pays off in this workload.

**SERIALIZABLE has a real cost, and it falls almost entirely on pessimistic, not optimistic.**
Averaged across the swept contention levels: optimistic's throughput under SERIALIZABLE differs
from READ COMMITTED by **0.3%** — statistical noise, not a real cost. Pessimistic's differs by
**61.4%** — `SELECT ... FOR UPDATE` under SERIALIZABLE pays PostgreSQL's SSI (Serializable
Snapshot Isolation) predicate-lock and conflict-detection overhead *on top of* the row lock it
already holds, while optimistic's plain read never takes a lock PostgreSQL has to track for SSI
purposes. Under SERIALIZABLE specifically, optimistic overtakes pessimistic below contention 32
and pessimistic only regains the lead at 32 and 64 — the one place in this data where the
ADR 0006-predicted shape actually shows up.

**Claim:** *Sustained 569 transfers/sec at p99 61 ms under serializable isolation (optimistic
strategy, contention 2 threads/hot account); under read_committed, pessimistic wins at every
contention level measured — there is no low-contention window where optimistic pays off in this
workload.*

## Why this contradicts ADR 0006's prediction, and why we believe the data

ADR 0006 predicted optimistic would be cheapest at low contention because it takes no lock. That
reasoning holds for the *lock*, but the benchmark's per-transfer cost is not lock-acquisition alone
— it is one round trip either way (`SELECT ... FOR UPDATE` vs plain `SELECT`, then one `UPDATE`
either way, unconditional vs `WHERE version = ?`). At contention=1 there is no lock to wait for
under either strategy, so the measured 10% gap is closer to noise than signal (this benchmark's
`SingleShotTime` samples are few — 30 transfers/thread per iteration, 3 iterations — and Docker on
a laptop is not a controlled lab). What is *not* noise is the pattern holding at every one of the 7
contention levels, in both isolation levels below the SERIALIZABLE crossover: that consistency,
not any single cell, is why this ADR treats "pessimistic wins under read_committed" as the real
finding rather than an artifact.

## Decision

**Production default: `ledger.concurrency-strategy=pessimistic`, `ledger.isolation-level=read_committed`.**
Both already were the defaults in `application.yml` before this spec — SPEC 0008's data confirms
them rather than changing them. Pessimistic wins or ties at every measured contention level under
READ COMMITTED, degrades more predictably under load (bounded by lock hold time, not an attempt
budget), and produces zero retry-exhausted failures across the entire READ COMMITTED sweep, where
optimistic accumulates hundreds at high contention. SERIALIZABLE is not adopted as the default:
its cost for the chosen strategy (pessimistic) is 61.4%, and ADR 0001's write-skew argument for
paying that cost does not apply to this access pattern.

**`ledger.concurrency.max-attempts` / `backoff-base-ms` are unchanged.** The retry-exhaustion
counts observed (up to 836 at contention=64 optimistic/read_committed) are a property of choosing
optimistic under high contention, not evidence the retry budget itself is miscalibrated; since
optimistic is not the default, retuning its retry budget is out of this ADR's scope.

**`DB_POOL_SIZE` is left at its current default (20), not raised.** The benchmark held pool size
fixed at 96 (comfortably above the highest contention level swept) across every cell specifically
*to remove* pool contention as a variable — so this data speaks to locking and isolation cost, not
to how a production pool should be sized against the pessimistic default's lock-hold-for-duration
behavior. Raising `DB_POOL_SIZE` on the strength of this run would be a guess dressed as a
measurement. A dedicated pool-size sweep (contention fixed at a realistic production level, pool
size as the swept variable) is the correct follow-up and is out of scope here.

## Consequences

**Positive:**
- Three previously-deferred decisions (`ConcurrencyStrategy` default, isolation-level default,
  ADR 0001's "what would change our mind") are now backed by a reproducible benchmark instead of
  intuition, satisfying FR-34/FR-35/NFR-8's measured-not-assumed requirement.
- SERIALIZABLE's cost is now a specific, citable number (61.4% for the chosen strategy) rather
  than an assumed "not free" — anyone proposing to flip isolation levels later has a real baseline
  to argue against.
- `make bench` is a real, reproducible artifact (`docs/bench/{results.md,results.csv,latency.svg}`)
  any future contributor can rerun and compare against.

**Negative / accepted costs:**
- The benchmark ran once (3 iterations per cell, one host, one Docker daemon) — not enough runs to
  report confidence intervals worth trusting past "the pattern is consistent across contention
  levels." `concurrency-test-x10`-style repeated runs of `make bench` would strengthen this if the
  numbers ever become load-bearing for an SLA (NFR-6 explicitly disclaims that framing today).
- Optimistic locking remains fully implemented and selectable (`CONCURRENCY_STRATEGY=optimistic`)
  even though it is not the default — SPEC 0008's data does not show it losing on *every* axis
  (it is the clear winner under SERIALIZABLE below contention 32), so ADR 0006's "retire the
  losing strategy" bar is not met.
- Pool sizing remains an open question; `DB_POOL_SIZE=20` is not verified against pessimistic's
  lock-hold-for-duration behavior at realistic production concurrency.

**What would change our mind:**
- A dedicated pool-size sweep showing 20 connections queue meaningfully under pessimistic at
  realistic production contention — would justify raising `DB_POOL_SIZE` with actual evidence.
- A future access pattern that reads one account to decide a write to a different one (ADR 0001's
  sharpest edge) — would force SERIALIZABLE regardless of this ADR's throughput argument, on
  correctness grounds that override cost.
- A repeated (`x10`-style) rerun of `make bench` showing the read_committed pattern here was itself
  noise, not signal — would reopen this decision.
