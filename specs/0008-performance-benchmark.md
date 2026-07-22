# SPEC 0008 — Performance benchmark

Status: implemented
Depends on: 0007
Requirements: FR-34, FR-35, NFR-6, NFR-7, NFR-8

## Goal

Quantify what the correctness guarantee **costs**. "I implemented both strategies, measured them
under contention, and found the crossover point" is a systems answer backed by data; "I picked
pessimistic because it felt safer" is not. This spec produces the numbers.

## In scope

- JMH and/or a load generator measuring transfers/sec and p50/p99 latency under contention.
- The 2×2 matrix: {optimistic, pessimistic} × {READ COMMITTED, SERIALIZABLE}.
- Contention swept across levels (threads per hot account) to **find the crossover point**.
- A results table and a latency plot.
- `make bench`.

## Out of scope

- Distributed scaling.

## Design notes

The expected shape — which the data must confirm or refute, not be assumed into existence:
optimistic wins at low contention (no lock acquisition cost, conflicts are rare) and loses at high
contention (retry storms — every retry is wasted work, and the wasted work itself increases
contention). Pessimistic pays a constant lock cost but degrades gracefully. Somewhere they cross.
**That crossover point is the deliverable.**

If the data contradicts this expectation, **report the data.** A surprising honest result is worth
more in an interview than a tidy expected one, and "here is where my intuition was wrong" is the
strongest thing a candidate can say.

Measure the cost of SERIALIZABLE against READ COMMITTED too: it prevents write skew and phantoms,
and it is not free. Naming the price of a guarantee is the point.

## Acceptance criteria (the measurable "done")

- [x] A results table: transfers/sec and p50/p99 for every cell of the 2×2 matrix.
      (`docs/bench/results.md`, `docs/bench/results.csv`)
- [x] The **contention crossover point** between optimistic and pessimistic is identified and
      stated as a number. Under READ COMMITTED, pessimistic wins at every level measured (crossover
      = 1, i.e. no window where optimistic pays off in this workload) — the data did not confirm
      ADR 0006's predicted low-contention optimistic advantage; see ADR 0010. Under SERIALIZABLE,
      the predicted shape does appear: optimistic wins below contention 32, pessimistic from 32 up.
- [x] The throughput cost of SERIALIZABLE vs READ COMMITTED is quantified as a percentage: 0.3%
      for optimistic, 61.4% for pessimistic (ADR 0010).
- [x] A latency plot is produced and committed. (`docs/bench/latency.svg`)
- [x] The claim is stated in one sentence: *"Sustained 569 transfers/sec at p99 61 ms under
      serializable isolation (optimistic strategy, contention 2 threads/hot account); under
      read_committed, pessimistic wins at every contention level measured — there is no
      low-contention window where optimistic pays off in this workload."*
- [x] `make bench` reproduces the table. `make bench-quick` runs a fast 3-level smoke variant.

## Test plan

`TransferBenchmark` (JMH) against Testcontainers Postgres. Warm-up iterations discarded. Report
percentiles, not just the mean — the mean hides exactly the tail behavior that matters under
contention.
