package org.ledger.saga;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SagaStepResult(
    UUID id,
    UUID sagaId,
    int stepIndex,
    String stepType,
    SagaStepState state,
    JsonNode forwardResult,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
