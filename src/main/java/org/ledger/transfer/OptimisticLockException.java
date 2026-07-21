package org.ledger.transfer;

/**
 * Placeholder exception type owned by SPEC 0004. Declared now, with no throw site yet, so {@code
 * GlobalExceptionHandler}'s {@code 409 CONFLICT_RETRY_EXHAUSTED} mapping (planning/05-api-design.md
 * §3.5) exists before {@code ConcurrencyStrategy} does. Lives in {@code transfer}, not {@code
 * concurrency}: {@code TransferService} is what coordinates {@code ConcurrencyStrategy} and would
 * surface an exhausted retry, and {@code api} may not depend on {@code concurrency} directly
 * (planning/03-system-design.md §1.2).
 */
public class OptimisticLockException extends RuntimeException {

  private final int attempts;

  public OptimisticLockException(int attempts) {
    super("Optimistic lock retry limit reached after " + attempts + " attempts");
    this.attempts = attempts;
  }

  public int attempts() {
    return attempts;
  }
}
