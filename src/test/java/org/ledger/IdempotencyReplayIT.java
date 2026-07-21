package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.count;
import static org.ledger.db.generated.tables.LedgerEntries.LEDGER_ENTRIES;
import static org.ledger.db.generated.tables.Transfers.TRANSFERS;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractApiIT;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 0003 — replaying one {@code Idempotency-Key} N times executes the underlying operation
 * exactly once; every retry gets the first response back byte-for-byte.
 */
class IdempotencyReplayIT extends AbstractApiIT {

  @Test
  void replayingSameKeyTenTimesAppliesTransferExactlyOnce() {
    UUID aliceId = createAccount("alice-replay", "USD");
    UUID bobId = createAccount("bob-replay", "USD");
    seedInitialBalance(aliceId, 10_000L);

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
    String key = "replay-key-" + UUID.randomUUID();

    ResponseEntity<Map> first =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, key),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(first.getHeaders().get("X-Idempotent-Replayed")).isNull();

    for (int i = 0; i < 9; i++) {
      ResponseEntity<Map> replay =
          rest.exchange(
              url("/api/v1/transfers"),
              HttpMethod.POST,
              authedWithIdempotencyKey(transferRequest, key),
              Map.class);
      assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(replay.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
      assertThat(replay.getBody()).isEqualTo(first.getBody());
    }

    assertThat(dsl.selectCount().from(TRANSFERS).fetchOne(count())).isEqualTo(1);
    assertThat(dsl.selectCount().from(LEDGER_ENTRIES).fetchOne(count())).isEqualTo(2);
  }

  @Test
  void replayingSameKeyOnAccountCreateCreatesExactlyOneAccount() {
    Map<String, Object> request = Map.of("name", "replay-account", "currency", "USD");
    String key = "replay-account-key-" + UUID.randomUUID();

    ResponseEntity<Map> first =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, key),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<Map> replay =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, key),
            Map.class);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getHeaders().getFirst("X-Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getBody()).isEqualTo(first.getBody());

    ResponseEntity<Map> list =
        rest.exchange(url("/api/v1/accounts?limit=100"), HttpMethod.GET, authed(null), Map.class);
    long matching =
        ((java.util.List<?>) list.getBody().get("data"))
            .stream().filter(a -> "replay-account".equals(((Map<?, ?>) a).get("name"))).count();
    assertThat(matching).isEqualTo(1);
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
