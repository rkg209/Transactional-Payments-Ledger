# ADR 0008 — Chain-of-legs saga model, and a single `LegTransferStep`

Date: 2026-07-22
Status: accepted
Deciders: Project owner
Relates to: SPEC 0006, FR-27, FR-28, FR-29, FR-30, FR-31, NFR-4, NFR-10, CON-7

## Context

SPEC 0006 asks for multi-leg transfers ("e.g. A→B→C, or hold → capture → settle") that either fully
complete or fully roll back, even across a crash. Two artifacts that predate this ADR disagree about
the shape of a saga's steps:

- `planning/05-openapi.yaml`'s `CreateSagaTransferRequest` describes a flat list of independent
  `{type: DEBIT|CREDIT, accountId, amount}` steps — a fan-out shape with no structural pairing
  between any two entries.
- `transfers` (`planning/04-database-schema.sql`) has a hard, `NOT NULL` two-account shape:
  `(from_account_id, to_account_id, amount_minor)`. There is no schema for a single-sided debit or
  credit that is not part of a `from → to` transfer.
- `planning/04-database-design.md` promises rationale sections §11.3–§11.5 explaining how a saga's
  `payload` maps onto accounts. Those sections were never written — this was a real gap, not
  something this ADR is silently overriding.
- `planning/03-system-design.md` sketches three step types — `DebitAccountStep`, `CreditAccountStep`,
  `RecordTransferStep` — implying each `DEBIT`/`CREDIT` entry becomes its own independent step, with
  a fourth step recording the transfer itself.

Every one of `ledger_entries`' balanced-pair guarantee, `transfers`' two-account shape, and this
project's own "every transfer's entries sum to zero" invariant assumes debits and credits arrive
paired. A fan-out list of independent single-sided steps has no way to reconstruct that pairing
without an extra convention layered on top — and the spec's own example (`A→B→C`) is already a chain
of ordinary transfers, not an arbitrary graph of unpaired debits and credits.

## Options considered

### Option A — Fan-out, as literally described by the openapi schema
- **Pros:** Matches the wire schema exactly; no deviation to document.
- **Cons:** Doesn't fit `transfers`' NOT NULL two-account shape at all — either `transfers` gets a
  migration to support single-sided rows (contradicting "the schema... was settled in planning,
  consult it, do not silently redesign it"), or each `DEBIT`/`CREDIT` step needs some other
  ledger-writing path that bypasses `transfers` entirely, which duplicates `TransferService`'s
  balance-guard and entry-pairing logic in a second place. Also does not obviously balance: nothing
  stops a client from submitting an odd number of steps, or debits that don't sum to credits, without
  an ad hoc validation layer re-deriving pairing after the fact.

### Option B — Chain-of-legs: steps are an alternating `DEBIT`/`CREDIT` sequence of equal-amount
pairs (chosen)
- **Pros:** Matches the spec's own worked example (`A→B→C`) exactly: leg 1 is `DEBIT A / CREDIT B`,
  leg 2 is `DEBIT B / CREDIT C`. Each pair is literally an ordinary transfer, so it can be posted
  through the already-proven, already-tested `TransferService.execute` completely unchanged —
  nothing about balance guards, entry pairing, or locking needs to be reimplemented for sagas. The
  wire schema (`steps: [SagaStepRequest]`) is kept as-is; the pairing is a validation rule
  (`@ValidSagaSteps`) on top of it, not a schema change.
- **Cons:** A client that actually wants an unpaired fan-out (e.g. one debit funding three credits)
  cannot express it under this model. Rejected as out of scope: SPEC 0006's own acceptance criteria
  and test plan only describe chains, and CON-7 already pushes true multi-party fan-out to the
  Message Broker project.

### Option C — Give `transfers` a nullable second account and write single-sided legs directly
- **Pros:** Would technically support the fan-out schema as written.
- **Cons:** Directly attacks the schema's `NOT NULL (from_account_id, to_account_id)` invariant,
  which `planning/04-database-schema.sql` is authoritative and explicitly not to be silently
  redesigned. It also reopens the balanced-entries question per single-sided step instead of per
  transfer, which is exactly the invariant `ledger_entries.insertBalancedPair` exists to make
  structurally impossible.

## Decision

**Steps are validated, paired chains of ordinary transfers.** `CreateSagaTransferRequest.steps`
keeps its flat `SagaStepRequest` wire shape; `@ValidSagaSteps` (a class-level bean-validation
constraint) requires an even-length list alternating `DEBIT`, `CREDIT`, with each pair sharing an
equal `amount` and different `accountId`s — mirroring `transfers_different_accounts`. A validated
request is reshaped, not re-validated, into `List<SagaLeg>` by `SagaController`, where each `SagaLeg`
is exactly the two-account, one-amount data a `transfers` row already requires.

**One `SagaStep` implementation, not three.** Because a leg already is a complete, balanced transfer,
`LegTransferStep` is the only `SagaStep`: `forward()` posts `leg.from → leg.to`; `compensate()` posts
a new, genuine reversing transfer `leg.to → leg.from` (never a delete — invariant #2). This replaces
`planning/03-system-design.md`'s `DebitAccountStep`/`CreditAccountStep`/`RecordTransferStep` sketch,
which was written for the fan-out model this ADR does not choose; those three types would have
nothing coherent to debit or credit independently under chain-of-legs, since every debit is already
paired with its credit before a step ever runs.

**Crash-safety: a deterministic idempotency key per (saga, step, direction).** `forward` uses
`saga:{sagaId}:step:{i}:forward`; `compensate` uses `saga:{sagaId}:step:{i}:compensate`. Both route
through `TransferService.execute` unchanged, which persists the key on `transfers.idempotency_key`
under `transfers_idempotency_key_uq`. `LegTransferStep` pre-checks `TransferRepository
.findByIdempotencyKey` before calling `TransferService.execute`, so a retried `forward`/`compensate`
(from `SagaOrchestrator.recover`) finds the leg that already committed and returns it, instead of
double-posting or racing the unique constraint. The same lookup lets `recover` disambiguate an
`IN_PROGRESS` step (persisted before `forward()` runs, so ambiguous by construction after a crash):
found means the leg committed and the step is really `COMPLETED`; not found means it never ran.

**Pre-accept ordering via a paused Tomcat connector, not runner ordering.** Spring Boot's
`ApplicationRunner`s fire after the embedded connector has already started accepting connections.
`TomcatConnectorPauseCustomizer` pauses the connector the instant it is created;
`SagaRecoveryRunner` resumes it (via `TomcatConnectorGate`) only after every recoverable saga has
been attempted. Listing recoverable sagas itself failing (not an individual saga's recovery failing)
is treated as fatal: the connector is deliberately left paused, so the service never silently accepts
traffic it cannot yet reconcile.

## Consequences

**Positive:**
- Every saga leg reuses `TransferService`'s already-benchmarked, already-concurrency-tested posting
  path — no second implementation of balance guards, entry pairing, or locking to keep correct.
- `SagaCompensatedException`/`SagaFailedException` and the `sagas`/`saga_steps` schema (already
  present, unmodified) turn out to fit this model with zero migration needed.
- Recovery idempotence follows directly from the same idempotency-key mechanism the rest of the
  system already relies on, rather than a bespoke recovery-specific dedup scheme.

**Negative / accepted costs:**
- `saga_steps.step_type` is `"LEG_TRANSFER"`, not one of the `DEBIT_ACCOUNT`/`CREDIT_ACCOUNT`/
  `RECORD_TRANSFER` values `planning/05-openapi.yaml`'s `SagaStepResponse.stepType` enum documents.
  That enum described the fan-out model; it is now stale documentation, not a contract this code
  violates at runtime (nothing generates code from it), but it should be corrected the next time that
  file is revisited.
- True fan-out sagas (one debit funding several credits, or vice versa) are out of scope under this
  model. If a future spec needs that, it is a new saga type and a new step implementation, not an
  extension of `LegTransferStep`.

**What would change our mind:** A concrete, in-scope requirement for an unpaired fan-out saga (e.g.
one account funding several recipients atomically) would force revisiting Option C or a genuine
multi-leg `SagaStep` that can debit once and credit several times inside one step.
