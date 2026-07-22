package org.ledger.infrastructure;

import java.time.Duration;

/**
 * SPEC 0010 — tuning for {@code org.ledger.idempotency.IdempotencyFilter}'s stale-claim recovery.
 *
 * <p>{@code staleClaimAfter} is how old an {@code IN_PROGRESS} claim must be before a retry may
 * reclaim it as abandoned by a dead process. It must stay comfortably above the bounded worst-case
 * duration of a guarded request (the transfer retry loop: {@code ledger.concurrency.max-attempts}
 * transactions plus backoff capped at 800 ms); the 30 s default is more than an order of magnitude
 * above that.
 *
 * <p>Correctness does not depend on this value being well chosen — {@code
 * transfers_idempotency_key_uq} rejects a double-post regardless (ADR 0012). Setting it too low
 * only trades a clean {@code 201} for an avoidable {@code 500}.
 */
public record LedgerIdempotencyProperties(Duration staleClaimAfter) {}
