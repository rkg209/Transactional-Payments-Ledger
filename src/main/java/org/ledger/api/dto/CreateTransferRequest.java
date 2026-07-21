package org.ledger.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * No {@code @Positive} on {@code amount}: a zero/negative amount is fixed at {@code 422
 * INVALID_AMOUNT} by {@link org.ledger.transfer.TransferService}, not {@code 400 VALIDATION_ERROR}.
 */
public record CreateTransferRequest(
    @NotNull UUID fromAccountId,
    @NotNull UUID toAccountId,
    @NotNull Long amount,
    @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {}
