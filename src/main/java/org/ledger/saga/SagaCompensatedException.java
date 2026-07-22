package org.ledger.saga;

import java.util.UUID;

/**
 * Thrown by {@link SagaOrchestrator#execute} when a step fails and the saga is rolled back via
 * compensation of every prior-completed step. Maps to {@code 422 SAGA_COMPENSATED}.
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
