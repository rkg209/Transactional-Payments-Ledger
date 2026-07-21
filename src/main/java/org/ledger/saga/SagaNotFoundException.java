package org.ledger.saga;

import java.util.UUID;

/**
 * Placeholder exception type owned by SPEC 0006. Declared now so {@code GlobalExceptionHandler}'s
 * {@code 404 SAGA_NOT_FOUND} mapping (planning/05-api-design.md §3.5) exists before the saga
 * orchestrator does.
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
