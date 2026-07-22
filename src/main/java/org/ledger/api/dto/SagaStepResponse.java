package org.ledger.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.ledger.saga.SagaStepResult;

public record SagaStepResponse(
    UUID id,
    UUID sagaId,
    int stepIndex,
    String stepType,
    String state,
    JsonNode forwardResult,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public static SagaStepResponse from(SagaStepResult result) {
    return new SagaStepResponse(
        result.id(),
        result.sagaId(),
        result.stepIndex(),
        result.stepType(),
        result.state().name(),
        result.forwardResult(),
        result.createdAt(),
        result.updatedAt());
  }
}
