# project-summary.md

```markdown
# Transactional Payments Ledger — Executive Summary

## What It Is

A production-shaped payments backend written in Java 21 that moves money between
accounts and **provably never loses, creates, or double-charges a single cent** —
even under thousands of simultaneous requests, retried payments, and mid-operation
crashes.

The system is built on double-entry bookkeeping: every transfer is recorded as a
balanced pair of entries (debit one account, credit another) that always sum to
zero. Balances are derived from an append-only, immutable ledger — the same
principle used by every serious financial system from bank ledgers to Stripe's
payment infrastructure. Correctness is not claimed; it is measured and
reproducible: run `make concurrency-test` on a clean machine and the results
table prints itself.

**Headline result:** 10,000 concurrent transfers with ~30% duplicate (idempotent)
requests against hot accounts → 0 double-charges, 0 money created or destroyed,
ledger invariant held across every run.

---

## Who It Is For

**Primary audience: fintech, payments, and backend-infrastructure interviewers**
— particularly at companies like Stripe, Brex, Plaid, Adyen, or any engineering
team that moves money or maintains critical shared state.

The questions this project answers directly are the ones those teams ask most:

- "How do you handle a payment request that gets retried?" (idempotency)
- "What isolation level would you choose and why?" (concurrency control)
- "How do you ensure a multi-step operation either fully completes or fully
  rolls back after a crash?" (sagas with compensation)
- "How do you know your ledger is correct?" (the continuously-checked invariant)

A candidate who has *built and measured* these things answers differently — and
more credibly — than one who has only read about them.

---

## Why It Matters

### The problem it solves

Any system that tracks balances under concurrent writes is vulnerable to a class
of subtle, financially damaging bugs: two requests racing to debit the same
account can both see a sufficient balance and both succeed, creating money from
nothing. A retried payment after a network timeout can charge a customer twice.
A multi-step transfer that crashes halfway leaves the books in an inconsistent
state with no clean path to recovery. Naive implementations — a single balance
column updated with a plain `UPDATE` — fail silently under all three scenarios.

### How this project solves it

| Problem | Solution implemented |
|---|---|
| Race conditions on shared balances | Optimistic locking (version/compare-and-set) and pessimistic locking (`SELECT … FOR UPDATE`), both implemented, both benchmarked, with a documented crossover point |
| Duplicate / retried requests | Idempotency keys with a unique database constraint; two identical concurrent requests produce exactly one transfer |
| Partial failures in multi-step operations | In-process saga orchestrator with persisted, crash-recoverable state and compensation actions |
| Silent corruption going undetected | Background reconciliation job continuously asserting `Σ(all entries) = 0`; any drift is flagged immediately |
| Floating-point money errors | All monetary values are integer minor units (`long` cents); `float`/`double` are mechanically blocked by a pre-commit hook |

### What makes it credible

The correctness guarantee is not a design claim — it is a reproducible test
result against a real PostgreSQL database (via Testcontainers), not an
in-memory fake. The concurrency harness injects crashes at every saga step,
fires thousands of parallel requests, and includes deliberate duplicate
submissions. The invariant check runs after every test run. The benchmark
produces a latency table comparing optimistic versus pessimistic strategies
under varying contention levels.

The engineering decisions — isolation level choice, locking strategy, saga
design — are each recorded as Architecture Decision Records with explicit
trade-off reasoning, the same artifact a senior engineer would produce on a
real team.

---

## The Technical Stack (one line each)

- **Java 21 + Spring Boot 3** — virtual threads for realistic high-concurrency
  throughput without the complexity of reactive programming
- **jOOQ** — type-safe SQL with explicit, compile-checked locking clauses;
  no ORM hiding when or what SQL fires
- **PostgreSQL** — real ACID transactions, real isolation levels, real behavior
  to reason about and measure
- **Flyway** — versioned, reproducible schema migrations
- **JUnit 5 + Testcontainers** — correctness tests run against a real database,
  not a mock
- **JMH** — micro-benchmark harness for the throughput and latency results table
- **Docker Compose + Make** — the headline result is one command on a clean
  machine

---

## The Deliverable in One Sentence

A public repository whose README opens with a measured correctness guarantee,
backed by a test harness any engineer can run, architecture decision records
explaining every significant choice, and a `make concurrency-test` command that
reproduces the headline number from scratch.
```