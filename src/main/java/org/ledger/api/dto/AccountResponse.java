package org.ledger.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.ledger.account.AccountResult;

public record AccountResponse(
    UUID id,
    String name,
    String currency,
    long minBalance,
    long balance,
    long version,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public static AccountResponse from(AccountResult result) {
    return new AccountResponse(
        result.id(),
        result.name(),
        result.currency(),
        result.minBalance(),
        result.balance(),
        result.version(),
        result.createdAt(),
        result.updatedAt());
  }
}
