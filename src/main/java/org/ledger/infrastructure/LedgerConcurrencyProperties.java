package org.ledger.infrastructure;

/**
 * Retry-loop tuning for {@code TransferService} (SPEC 0004). {@code ledger.concurrency-strategy}
 * and {@code ledger.isolation-level} are read directly in {@link ConcurrencyConfig}, since they
 * pick beans rather than tune a value.
 */
public record LedgerConcurrencyProperties(int maxAttempts, long backoffBaseMs) {}
