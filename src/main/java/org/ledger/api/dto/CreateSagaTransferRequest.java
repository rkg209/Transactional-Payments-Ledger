package org.ledger.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Mirrors {@code CreateSagaTransferRequest} in {@code planning/05-openapi.yaml}. {@code steps} is
 * the fan-out-shaped wire format the spec settled on; {@link ValidSagaSteps} enforces the
 * chain-of-legs pairing ADR 0008 requires underneath it, and {@code SagaController} converts a
 * validated request into a {@code SagaDefinition}.
 */
@ValidSagaSteps
public record CreateSagaTransferRequest(
    @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @NotNull @Size(min = 2) @Valid List<SagaStepRequest> steps,
    @Size(max = 500) String description) {}
