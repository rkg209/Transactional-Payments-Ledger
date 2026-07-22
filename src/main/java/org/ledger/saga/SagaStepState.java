package org.ledger.saga;

/**
 * Mirrors the {@code saga_steps.state} CHECK constraint in {@code V1__initial_schema.sql}. {@code
 * PENDING} exists for schema completeness only -- {@link SagaOrchestrator} always inserts a step
 * row as {@code IN_PROGRESS} (persist-intent-before-acting), so no row is ever observed in {@code
 * PENDING} state.
 */
public enum SagaStepState {
  PENDING,
  IN_PROGRESS,
  COMPLETED,
  COMPENSATED
}
