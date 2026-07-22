package org.ledger.saga;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One unit of saga work. Under the chain-of-legs model (ADR 0008) there is a single implementation,
 * {@link LegTransferStep} -- one full leg (debit one account, credit another), not the three-step
 * fan-out sketch in {@code planning/03-system-design.md}.
 */
public interface SagaStep {

  /** Persisted to {@code saga_steps.step_type}. */
  String stepType();

  /** Performs the forward action. Result is persisted to {@code saga_steps.forward_result}. */
  JsonNode forward(SagaContext ctx);

  /**
   * Reverses the forward action with a genuine new compensating operation -- never a delete
   * (invariant #2). {@code forwardResult} is whatever {@link #forward} returned, as persisted;
   * implementations that can reverse from {@code ctx} alone are free to ignore it.
   */
  void compensate(SagaContext ctx, JsonNode forwardResult);
}
