package org.ledger.saga;

import java.util.UUID;

/**
 * Placeholder exception type owned by SPEC 0006 — see {@link SagaNotFoundException}. Maps to {@code
 * 422 SAGA_COMPENSATED}.
 */
public class SagaCompensatedException extends RuntimeException {

  private final UUID sagaId;
  private final int compensatedSteps;

  public SagaCompensatedException(UUID sagaId, int compensatedSteps) {
    super(
        "Saga %s was rolled back via compensation (%d steps)".formatted(sagaId, compensatedSteps));
    this.sagaId = sagaId;
    this.compensatedSteps = compensatedSteps;
  }

  public UUID sagaId() {
    return sagaId;
  }

  public int compensatedSteps() {
    return compensatedSteps;
  }
}
