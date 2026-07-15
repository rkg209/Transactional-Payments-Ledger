package org.ledger.transfer;

import java.util.UUID;

/**
 * Thrown when a transfer would push the source account below its {@code min_balance}. Raised before
 * any write, so no rollback of already-written entries is ever needed on this path.
 */
public class InsufficientFundsException extends RuntimeException {

  private final UUID accountId;
  private final long availableBalance;
  private final long requiredAmount;
  private final String currency;

  public InsufficientFundsException(
      UUID accountId, long availableBalance, long requiredAmount, String currency) {
    super(
        "Account %s has %d %s available but the transfer requires %d %s"
            .formatted(accountId, availableBalance, currency, requiredAmount, currency));
    this.accountId = accountId;
    this.availableBalance = availableBalance;
    this.requiredAmount = requiredAmount;
    this.currency = currency;
  }

  public UUID accountId() {
    return accountId;
  }

  public long availableBalance() {
    return availableBalance;
  }

  public long requiredAmount() {
    return requiredAmount;
  }

  public String currency() {
    return currency;
  }
}
