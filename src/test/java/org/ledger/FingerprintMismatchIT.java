package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.count;
import static org.ledger.db.generated.tables.Transfers.TRANSFERS;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractApiIT;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 0003 — reusing an {@code Idempotency-Key} with a different request body is a client error,
 * not a replay: {@code 422 IDEMPOTENCY_KEY_REUSE}, and the second request never executes.
 */
class FingerprintMismatchIT extends AbstractApiIT {

  @Test
  void reusedKeyWithDifferentAmountIs422AndDoesNotExecute() {
    UUID aliceId = createAccount("alice-fp", "USD");
    UUID bobId = createAccount("bob-fp", "USD");
    seedInitialBalance(aliceId, 10_000L);
    String key = "fp-key-" + UUID.randomUUID();

    Map<String, Object> firstRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            bobId.toString(),
            "amount",
            1_000,
            "currency",
            "USD");
    ResponseEntity<Map> first =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(firstRequest, key),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    Map<String, Object> secondRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            bobId.toString(),
            "amount",
            2_000,
            "currency",
            "USD");
    ResponseEntity<Map> second =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(secondRequest, key),
            Map.class);

    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(second.getBody().get("errorCode")).isEqualTo("IDEMPOTENCY_KEY_REUSE");
    assertThat(((Map<?, ?>) second.getBody().get("details")).get("key")).isEqualTo(key);

    assertThat(dsl.selectCount().from(TRANSFERS).fetchOne(count())).isEqualTo(1);

    ResponseEntity<Map> aliceBalance =
        rest.exchange(
            url("/api/v1/accounts/" + aliceId + "/balance"),
            HttpMethod.GET,
            authed(null),
            Map.class);
    assertThat(((Number) aliceBalance.getBody().get("balance")).longValue()).isEqualTo(9_000L);
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
