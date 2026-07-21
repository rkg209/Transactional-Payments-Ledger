package org.ledger.api.error;

import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ledger.account.AccountNotFoundException;
import org.ledger.api.dto.ErrorResponse;
import org.ledger.api.pagination.InvalidCursorException;
import org.ledger.idempotency.IdempotencyFingerprintMismatchException;
import org.ledger.idempotency.IdempotencyTimeoutException;
import org.ledger.saga.SagaCompensatedException;
import org.ledger.saga.SagaNotFoundException;
import org.ledger.transfer.CurrencyMismatchException;
import org.ledger.transfer.InsufficientFundsException;
import org.ledger.transfer.InvalidAmountException;
import org.ledger.transfer.OptimisticLockException;
import org.ledger.transfer.SameAccountException;
import org.ledger.transfer.TransferNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps every domain and framework exception reachable in this codebase to the standard {@link
 * ErrorResponse} shape (planning/05-api-design.md §3.5). Handlers for exceptions whose triggers
 * land in later specs are wired now against exception types already declared in the packages their
 * own spec will own, so SPEC 0003/0004/0006 add throw sites, not handler edits.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(InsufficientFundsException.class)
  public ResponseEntity<ErrorResponse> handle(InsufficientFundsException e) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("accountId", e.accountId());
    details.put("availableBalance", e.availableBalance());
    details.put("requiredAmount", e.requiredAmount());
    details.put("currency", e.currency());
    return respond(ErrorCode.INSUFFICIENT_FUNDS, e.getMessage(), details);
  }

  @ExceptionHandler(AccountNotFoundException.class)
  public ResponseEntity<ErrorResponse> handle(AccountNotFoundException e) {
    return respond(ErrorCode.ACCOUNT_NOT_FOUND, e.getMessage(), Map.of("accountId", e.accountId()));
  }

  @ExceptionHandler(TransferNotFoundException.class)
  public ResponseEntity<ErrorResponse> handle(TransferNotFoundException e) {
    return respond(
        ErrorCode.TRANSFER_NOT_FOUND, e.getMessage(), Map.of("transferId", e.transferId()));
  }

  @ExceptionHandler(SagaNotFoundException.class)
  public ResponseEntity<ErrorResponse> handle(SagaNotFoundException e) {
    return respond(ErrorCode.SAGA_NOT_FOUND, e.getMessage(), Map.of("sagaId", e.sagaId()));
  }

  @ExceptionHandler(OptimisticLockException.class)
  public ResponseEntity<ErrorResponse> handle(OptimisticLockException e) {
    return respond(
        ErrorCode.CONFLICT_RETRY_EXHAUSTED, e.getMessage(), Map.of("attempts", e.attempts()));
  }

  @ExceptionHandler(IdempotencyFingerprintMismatchException.class)
  public ResponseEntity<ErrorResponse> handle(IdempotencyFingerprintMismatchException e) {
    return respond(ErrorCode.IDEMPOTENCY_KEY_REUSE, e.getMessage(), Map.of("key", e.key()));
  }

  @ExceptionHandler(SagaCompensatedException.class)
  public ResponseEntity<ErrorResponse> handle(SagaCompensatedException e) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("sagaId", e.sagaId());
    details.put("compensatedSteps", e.compensatedSteps());
    return respond(ErrorCode.SAGA_COMPENSATED, e.getMessage(), details);
  }

  @ExceptionHandler(CurrencyMismatchException.class)
  public ResponseEntity<ErrorResponse> handle(CurrencyMismatchException e) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("fromCurrency", e.fromCurrency());
    details.put("toCurrency", e.toCurrency());
    return respond(ErrorCode.CURRENCY_MISMATCH, e.getMessage(), details);
  }

  @ExceptionHandler(SameAccountException.class)
  public ResponseEntity<ErrorResponse> handle(SameAccountException e) {
    return respond(ErrorCode.SAME_ACCOUNT_TRANSFER, e.getMessage(), null);
  }

  @ExceptionHandler(InvalidAmountException.class)
  public ResponseEntity<ErrorResponse> handle(InvalidAmountException e) {
    return respond(ErrorCode.INVALID_AMOUNT, e.getMessage(), Map.of("amount", e.amount()));
  }

  @ExceptionHandler(InvalidCursorException.class)
  public ResponseEntity<ErrorResponse> handle(InvalidCursorException e) {
    return respond(ErrorCode.VALIDATION_ERROR, e.getMessage(), null);
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
  public ResponseEntity<ErrorResponse> handle(Exception e) {
    List<Map<String, String>> violations;
    if (e instanceof MethodArgumentNotValidException manve) {
      violations =
          manve.getBindingResult().getFieldErrors().stream()
              .map(GlobalExceptionHandler::toViolation)
              .toList();
    } else {
      ConstraintViolationException cve = (ConstraintViolationException) e;
      violations =
          cve.getConstraintViolations().stream()
              .map(
                  v ->
                      Map.of(
                          "field", v.getPropertyPath().toString(),
                          "message", v.getMessage()))
              .toList();
    }
    return respond(
        ErrorCode.VALIDATION_ERROR,
        ErrorCode.VALIDATION_ERROR.defaultMessage(),
        Map.of("violations", violations));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handle(HttpMessageNotReadableException e) {
    return respond(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.defaultMessage(), null);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ErrorResponse> handle(MissingRequestHeaderException e) {
    if ("Idempotency-Key".equals(e.getHeaderName())) {
      return respond(
          ErrorCode.MISSING_IDEMPOTENCY_KEY,
          ErrorCode.MISSING_IDEMPOTENCY_KEY.defaultMessage(),
          null);
    }
    return respond(ErrorCode.VALIDATION_ERROR, e.getMessage(), null);
  }

  @ExceptionHandler(IdempotencyTimeoutException.class)
  public ResponseEntity<ErrorResponse> handle(IdempotencyTimeoutException e) {
    return respond(ErrorCode.IDEMPOTENCY_TIMEOUT, e.getMessage(), Map.of("key", e.key()));
  }

  @ExceptionHandler(Throwable.class)
  public ResponseEntity<ErrorResponse> handle(Throwable e) {
    log.error("Unhandled exception, requestId={}", MDC.get("requestId"), e);
    return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null);
  }

  private static Map<String, String> toViolation(FieldError fieldError) {
    return Map.of("field", fieldError.getField(), "message", fieldError.getDefaultMessage());
  }

  private static ResponseEntity<ErrorResponse> respond(
      ErrorCode code, String message, Map<String, Object> details) {
    ErrorResponse body =
        new ErrorResponse(
            code.name(),
            message,
            MDC.get(org.ledger.api.filter.RequestIdFilter.MDC_KEY),
            OffsetDateTime.now(),
            details);
    return ResponseEntity.status(code.status())
        .header(HttpHeaders.CONTENT_TYPE, "application/json")
        .body(body);
  }
}
