package org.ledger.transfer;

public class SameAccountException extends RuntimeException {

  public SameAccountException() {
    super("fromAccountId and toAccountId must not be the same account");
  }
}
