package org.ledger.transfer;

public class InvalidAmountException extends RuntimeException {

  private final long amount;

  public InvalidAmountException(long amount) {
    super("Invalid transfer amount: " + amount);
    this.amount = amount;
  }

  public long amount() {
    return amount;
  }
}
