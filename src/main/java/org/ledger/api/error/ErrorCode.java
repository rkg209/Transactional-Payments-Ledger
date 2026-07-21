package org.ledger.api.error;

import org.springframework.http.HttpStatus;

/**
 * The complete, stable error-code catalogue from planning/05-api-design.md §3.2. One constant per
 * row, each carrying its fixed {@link HttpStatus} — status is structurally tied to the code here,
 * not scattered across handler methods.
 */
public enum ErrorCode {
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed."),
  MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required."),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body could not be parsed."),
  MISSING_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Authorization header is required."),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "The provided API key is not valid."),
  ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "Account not found."),
  TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Transfer not found."),
  SAGA_NOT_FOUND(HttpStatus.NOT_FOUND, "Saga not found."),
  CONFLICT_RETRY_EXHAUSTED(HttpStatus.CONFLICT, "Optimistic lock retry limit reached."),
  INSUFFICIENT_FUNDS(
      HttpStatus.UNPROCESSABLE_ENTITY,
      "The source account does not have sufficient funds for this transfer."),
  CURRENCY_MISMATCH(
      HttpStatus.UNPROCESSABLE_ENTITY,
      "Source and destination accounts have different currencies."),
  SAME_ACCOUNT_TRANSFER(
      HttpStatus.UNPROCESSABLE_ENTITY, "fromAccountId and toAccountId must not be the same."),
  IDEMPOTENCY_KEY_REUSE(
      HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key was reused with a different request body."),
  SAGA_COMPENSATED(
      HttpStatus.UNPROCESSABLE_ENTITY, "Saga completed but was rolled back via compensation."),
  INVALID_AMOUNT(
      HttpStatus.UNPROCESSABLE_ENTITY, "Amount is zero, negative, or exceeds system maximum."),
  IDEMPOTENCY_TIMEOUT(
      HttpStatus.SERVICE_UNAVAILABLE,
      "Concurrent duplicate request did not resolve within the timeout."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}
