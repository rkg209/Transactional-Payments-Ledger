package org.ledger.idempotency;

/** Mirrors the {@code idempotency_keys_status_valid} CHECK constraint's three values. */
public enum IdempotencyStatus {
  IN_PROGRESS,
  COMPLETED,
  FAILED
}
