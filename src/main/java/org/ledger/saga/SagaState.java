package org.ledger.saga;

/** Mirrors the {@code sagas.state} CHECK constraint in {@code V1__initial_schema.sql}. */
public enum SagaState {
  STARTED,
  COMPLETED,
  COMPENSATING,
  COMPENSATED,
  FAILED
}
