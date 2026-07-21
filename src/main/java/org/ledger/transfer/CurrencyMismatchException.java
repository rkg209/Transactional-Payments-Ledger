package org.ledger.transfer;

public class CurrencyMismatchException extends RuntimeException {

  private final String fromCurrency;
  private final String toCurrency;

  public CurrencyMismatchException(String fromCurrency, String toCurrency) {
    super("Currency mismatch: from=%s to=%s".formatted(fromCurrency, toCurrency));
    this.fromCurrency = fromCurrency;
    this.toCurrency = toCurrency;
  }

  public String fromCurrency() {
    return fromCurrency;
  }

  public String toCurrency() {
    return toCurrency;
  }
}
