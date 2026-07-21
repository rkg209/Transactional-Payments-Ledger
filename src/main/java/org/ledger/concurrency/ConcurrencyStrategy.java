package org.ledger.concurrency;

import java.util.UUID;

/**
 * The single seam where optimistic and pessimistic locking differ (SPEC 0004, ADR 0006). Both
 * implementations lock rows in ascending id order inside {@link #lockAndLoad} — that ordering is
 * the deadlock argument (ADR 0001) and must be structural, not left to the caller.
 */
public interface ConcurrencyStrategy {

  /** Loads both accounts under this strategy's locking discipline, ascending id order. */
  LockedAccounts lockAndLoad(UUID a, UUID b);

  /**
   * Applies the signed delta to {@code accountId}. Optimistic compares versions and throws {@link
   * ConcurrencyConflictException} on a lost race; pessimistic relies on the row lock already held
   * by {@link #lockAndLoad}.
   */
  void applyDelta(LockedAccounts accounts, UUID accountId, long delta);

  String name();
}
