package org.ledger.saga;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SagaResult(
    UUID id,
    String type,
    SagaState state,
    int currentStep,
    String currency,
    String description,
    List<SagaStepResult> steps,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
