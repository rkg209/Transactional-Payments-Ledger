package org.ledger.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.ledger.saga.SagaResult;

public record SagaResponse(
    UUID id,
    String type,
    String state,
    int currentStep,
    String currency,
    String description,
    List<SagaStepResponse> steps,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public static SagaResponse from(SagaResult result) {
    return new SagaResponse(
        result.id(),
        result.type(),
        result.state().name(),
        result.currentStep(),
        result.currency(),
        result.description(),
        result.steps().stream().map(SagaStepResponse::from).toList(),
        result.createdAt(),
        result.updatedAt());
  }
}
