package org.ledger.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
    @PositiveOrZero Long minBalance) {}
