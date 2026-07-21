package org.ledger.idempotency;

/**
 * Placeholder exception type owned by SPEC 0003. Declared now, with no throw site yet, so {@code
 * GlobalExceptionHandler}'s {@code 422 IDEMPOTENCY_KEY_REUSE} mapping (planning/05-api-design.md
 * §3.5) exists before the idempotency gate does; SPEC 0003 adds the trigger, not a handler edit.
 */
public class IdempotencyFingerprintMismatchException extends RuntimeException {

  private final String key;

  public IdempotencyFingerprintMismatchException(String key) {
    super("Idempotency-Key %s was reused with a different request body".formatted(key));
    this.key = key;
  }

  public String key() {
    return key;
  }
}
