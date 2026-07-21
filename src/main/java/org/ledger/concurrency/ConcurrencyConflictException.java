package org.ledger.concurrency;

import java.util.UUID;

/**
 * Thrown by {@link OptimisticStrategy#applyDelta} when the version compare-and-set updates zero
 * rows: another transaction committed against the same account first. Lives in {@code concurrency},
 * not {@code transfer} ({@code concurrency} may not depend on {@code transfer} —
 * planning/03-system-design.md §1.2); {@code TransferService} catches this per retry attempt and
 * translates an exhausted retry budget into {@code transfer.OptimisticLockException}.
 */
public class ConcurrencyConflictException extends RuntimeException {

  private final UUID accountId;

  public ConcurrencyConflictException(UUID accountId) {
    super("Concurrent update lost the race for account " + accountId);
    this.accountId = accountId;
  }

  public UUID accountId() {
    return accountId;
  }
}
