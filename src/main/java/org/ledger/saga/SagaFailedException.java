package org.ledger.saga;

import java.util.UUID;

/**
 * Thrown when a saga's {@code state} is (or transitions synchronously to) {@code FAILED} --
 * compensation itself failed, so the system is genuinely inconsistent and needs a human
 * (reconciliation will flag the drift). No dedicated {@code GlobalExceptionHandler} entry: it falls
 * through to the generic {@code Throwable} handler, which is exactly the {@code 500 INTERNAL_ERROR}
 * this state deserves -- the error-code catalogue in {@code planning/05-api-design.md} §3.2 has no
 * client-facing code for it, and none is warranted.
 */
public class SagaFailedException extends RuntimeException {

  private final UUID sagaId;

  public SagaFailedException(UUID sagaId) {
    super(
        "Saga %s transitioned to FAILED: compensation itself failed and requires manual intervention"
            .formatted(sagaId));
    this.sagaId = sagaId;
  }

  public UUID sagaId() {
    return sagaId;
  }
}
