package org.ledger.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One entry in {@link CreateSagaTransferRequest#steps()}. {@code type}/pairing/amount/account
 * cross-checks live in {@link SagaStepsValidator}, not here -- they need the whole list, not one
 * element.
 */
public record SagaStepRequest(
    @NotNull String type, @NotNull UUID accountId, @NotNull Long amount) {}
