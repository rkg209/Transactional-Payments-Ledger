package org.ledger.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.ledger.account.AccountResult;

/**
 * {@code asOf} is the account's {@code updatedAt}: the last committed transfer, not wall-clock
 * time.
 */
public record BalanceResponse(
    UUID accountId, String currency, long balance, long minBalance, OffsetDateTime asOf) {

  public static BalanceResponse from(AccountResult result) {
    return new BalanceResponse(
        result.id(), result.currency(), result.balance(), result.minBalance(), result.updatedAt());
  }
}
