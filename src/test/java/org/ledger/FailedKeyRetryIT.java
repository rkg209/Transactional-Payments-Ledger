package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ledger.db.generated.tables.IdempotencyKeys.IDEMPOTENCY_KEYS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractApiIT;
import org.ledger.transfer.TransferService;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 0003 — decision 1: a 5xx marks the key {@code FAILED} (retryable); a 4xx still marks it
 * {@code COMPLETED} (replayed, not retried). {@code @SpyBean} isolates this class from other
 * idempotency tests: it wraps the real {@link TransferService}, stubbed to throw on exactly its
 * first invocation and fall through to the real implementation afterwards.
 */
class FailedKeyRetryIT extends AbstractApiIT {

  @SpyBean private TransferService transferService;

  @Test
  void failedAttemptMarksKeyFailedAndTheRetrySucceeds() {
    UUID aliceId = createAccount("alice-fk", "USD");
    UUID bobId = createAccount("bob-fk", "USD");
    seedInitialBalance(aliceId, 10_000L);
    String key = "failed-retry-key-" + UUID.randomUUID();

    doThrow(new RuntimeException("simulated failure"))
        .doCallRealMethod()
        .when(transferService)
        .execute(any(), any(), anyLong(), anyString(), anyString());

    Map<String, Object> transferRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            bobId.toString(),
            "amount",
            1_000,
            "currency",
            "USD");

    ResponseEntity<Map> firstAttempt =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, key),
            Map.class);
    assertThat(firstAttempt.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    String status =
        dsl.select(IDEMPOTENCY_KEYS.STATUS)
            .from(IDEMPOTENCY_KEYS)
            .where(IDEMPOTENCY_KEYS.KEY.eq(key))
            .fetchOne(IDEMPOTENCY_KEYS.STATUS);
    assertThat(status).isEqualTo("FAILED");

    ResponseEntity<Map> retry =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, key),
            Map.class);
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String retryStatus =
        dsl.select(IDEMPOTENCY_KEYS.STATUS)
            .from(IDEMPOTENCY_KEYS)
            .where(IDEMPOTENCY_KEYS.KEY.eq(key))
            .fetchOne(IDEMPOTENCY_KEYS.STATUS);
    assertThat(retryStatus).isEqualTo("COMPLETED");
  }

  @Test
  void fourXxFirstAttemptLandsAsCompletedAndReplays() {
    UUID aliceId = createAccount("alice-fk-4xx", "USD");
    UUID bobId = createAccount("bob-fk-4xx", "USD");
    seedAccountState(aliceId, 100L, 0L);
    String key = "failed-retry-4xx-key-" + UUID.randomUUID();

    Map<String, Object> transferRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            bobId.toString(),
            "amount",
            1_000,
            "currency",
            "USD");

    ResponseEntity<Map> firstAttempt =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, key),
            Map.class);
    assertThat(firstAttempt.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

    String status =
        dsl.select(IDEMPOTENCY_KEYS.STATUS)
            .from(IDEMPOTENCY_KEYS)
            .where(IDEMPOTENCY_KEYS.KEY.eq(key))
            .fetchOne(IDEMPOTENCY_KEYS.STATUS);
    assertThat(status).isEqualTo("COMPLETED");

    ResponseEntity<Map> replay =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, key),
            Map.class);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getBody()).isEqualTo(firstAttempt.getBody());
  }

  private UUID createAccount(String name, String currency) {
    Map<String, Object> request = Map.of("name", name, "currency", currency);
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, UUID.randomUUID().toString()),
            Map.class);
    return UUID.fromString((String) response.getBody().get("id"));
  }
}
