package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.api.error.ErrorCode;
import org.ledger.api.error.GlobalExceptionHandler;
import org.ledger.idempotency.IdempotencyFingerprintMismatchException;
import org.ledger.idempotency.IdempotencyTimeoutException;
import org.ledger.saga.SagaCompensatedException;
import org.ledger.saga.SagaNotFoundException;
import org.ledger.transfer.OptimisticLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Proves the §3.5 mapping for exceptions whose *trigger* lands in a later spec (0003/0004/0006)
 * before that spec exists, per the SPEC 0002 plan: the handler is correct now, so those specs add a
 * throw site, not a handler edit.
 */
class GlobalExceptionHandlerUnitTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void idempotencyFingerprintMismatchMapsTo422IdempotencyKeyReuse() {
    ResponseEntity<?> response =
        handler.handle(new IdempotencyFingerprintMismatchException("key-1"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(body(response).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSE.name());
  }

  @Test
  void idempotencyTimeoutMapsTo503IdempotencyTimeout() {
    ResponseEntity<?> response = handler.handle(new IdempotencyTimeoutException("key-1"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(body(response).errorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_TIMEOUT.name());
  }

  @Test
  void optimisticLockExhaustedMapsTo409ConflictRetryExhausted() {
    ResponseEntity<?> response = handler.handle(new OptimisticLockException(5));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(body(response).errorCode()).isEqualTo(ErrorCode.CONFLICT_RETRY_EXHAUSTED.name());
  }

  @Test
  void sagaCompensatedMapsTo422SagaCompensated() {
    ResponseEntity<?> response = handler.handle(new SagaCompensatedException(UUID.randomUUID(), 3));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(body(response).errorCode()).isEqualTo(ErrorCode.SAGA_COMPENSATED.name());
  }

  @Test
  void sagaNotFoundMapsTo404SagaNotFound() {
    ResponseEntity<?> response = handler.handle(new SagaNotFoundException(UUID.randomUUID()));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(body(response).errorCode()).isEqualTo(ErrorCode.SAGA_NOT_FOUND.name());
  }

  private static org.ledger.api.dto.ErrorResponse body(ResponseEntity<?> response) {
    return (org.ledger.api.dto.ErrorResponse) response.getBody();
  }
}
