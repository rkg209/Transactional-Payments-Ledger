package org.ledger.idempotency;

/**
 * Placeholder exception type owned by SPEC 0003 — see {@link
 * IdempotencyFingerprintMismatchException}. Maps to {@code 503 IDEMPOTENCY_TIMEOUT}.
 */
public class IdempotencyTimeoutException extends RuntimeException {

  private final String key;

  public IdempotencyTimeoutException(String key) {
    super(
        "Concurrent duplicate request for Idempotency-Key %s did not resolve in time"
            .formatted(key));
    this.key = key;
  }

  public String key() {
    return key;
  }
}
