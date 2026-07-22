package org.ledger.saga;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.ledger.db.TransferRepository;
import org.ledger.db.generated.tables.records.TransfersRecord;
import org.ledger.transfer.TransferService;

/**
 * The one {@link SagaStep} implementation under the chain-of-legs model (ADR 0008): one leg = one
 * ordinary transfer, posted through the already-proven {@link TransferService#execute} unchanged.
 *
 * <p>Crash-safety comes from a deterministic idempotency key per (saga, step, direction) -- {@link
 * #forwardKey} / {@link #compensateKey} -- combined with a pre-insert lookup by that key ({@link
 * TransferRepository#findByIdempotencyKey}). A retried {@link #forward} or {@link #compensate}
 * (from {@link SagaOrchestrator#recover}) finds the leg that already committed and returns it,
 * rather than re-executing or racing {@code transfers_idempotency_key_uq}.
 */
final class LegTransferStep implements SagaStep {

  static final String STEP_TYPE = "LEG_TRANSFER";

  private final TransferService transferService;
  private final TransferRepository transferRepository;
  private final SagaLeg leg;

  LegTransferStep(
      TransferService transferService, TransferRepository transferRepository, SagaLeg leg) {
    this.transferService = transferService;
    this.transferRepository = transferRepository;
    this.leg = leg;
  }

  @Override
  public String stepType() {
    return STEP_TYPE;
  }

  @Override
  public JsonNode forward(SagaContext ctx) {
    String key = forwardKey(ctx.sagaId(), leg.stepIndex());
    UUID transferId =
        executeIdempotently(key, leg.fromAccountId(), leg.toAccountId(), ctx.currency());
    return transferResultJson(transferId);
  }

  @Override
  public void compensate(SagaContext ctx, JsonNode forwardResult) {
    String key = compensateKey(ctx.sagaId(), leg.stepIndex());
    // A genuine new reversing transfer -- to/from swapped -- never a delete (invariant #2).
    executeIdempotently(key, leg.toAccountId(), leg.fromAccountId(), ctx.currency());
  }

  private UUID executeIdempotently(String idempotencyKey, UUID from, UUID to, String currency) {
    return transferRepository
        .findByIdempotencyKey(idempotencyKey)
        .map(TransfersRecord::getId)
        .orElseGet(
            () ->
                transferService
                    .execute(from, to, leg.amountMinor(), currency, idempotencyKey)
                    .transferId());
  }

  static String forwardKey(UUID sagaId, int stepIndex) {
    return "saga:%s:step:%d:forward".formatted(sagaId, stepIndex);
  }

  static String compensateKey(UUID sagaId, int stepIndex) {
    return "saga:%s:step:%d:compensate".formatted(sagaId, stepIndex);
  }

  static JsonNode transferResultJson(UUID transferId) {
    return SagaJson.MAPPER.createObjectNode().put("transferId", transferId.toString());
  }
}
