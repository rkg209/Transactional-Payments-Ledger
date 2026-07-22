package org.ledger.saga;

import java.util.UUID;

/**
 * Thrown by {@link SagaOrchestrator#getSaga} when no saga exists with the given id. Maps to {@code
 * 404 SAGA_NOT_FOUND} (planning/05-api-design.md §3.5).
 */
public class SagaNotFoundException extends RuntimeException {

  private final UUID sagaId;

  public SagaNotFoundException(UUID sagaId) {
    super("Saga not found: " + sagaId);
    this.sagaId = sagaId;
  }

  public UUID sagaId() {
    return sagaId;
  }
}
