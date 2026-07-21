package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.count;
import static org.ledger.db.generated.tables.LedgerEntries.LEDGER_ENTRIES;
import static org.ledger.db.generated.tables.Transfers.TRANSFERS;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.ledger.support.AbstractApiIT;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 0003 — the test that matters. Two threads race the same {@code Idempotency-Key} against the
 * same body; the unique-constraint claim in {@code idempotency_keys} must let exactly one execute
 * and make the other replay, never both.
 */
class ConcurrentDuplicateIT extends AbstractApiIT {

  @RepeatedTest(100)
  void concurrentDuplicateRequestsApplyExactlyOnce() throws Exception {
    UUID aliceId = createAccount("alice-cd-" + UUID.randomUUID(), "USD");
    UUID bobId = createAccount("bob-cd-" + UUID.randomUUID(), "USD");
    seedInitialBalance(aliceId, 10_000L);
    String key = "cd-key-" + UUID.randomUUID();

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

    CyclicBarrier barrier = new CyclicBarrier(2);
    Callable<ResponseEntity<Map>> task =
        () -> {
          barrier.await();
          return rest.exchange(
              url("/api/v1/transfers"),
              HttpMethod.POST,
              authedWithIdempotencyKey(transferRequest, key),
              Map.class);
        };

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      Future<ResponseEntity<Map>> f1 = executor.submit(task);
      Future<ResponseEntity<Map>> f2 = executor.submit(task);
      ResponseEntity<Map> r1 = f1.get();
      ResponseEntity<Map> r2 = f2.get();

      assertThat(r1.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
      assertThat(r2.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
      assertThat(r1.getBody()).isEqualTo(r2.getBody());

      assertThat(dsl.selectCount().from(TRANSFERS).fetchOne(count())).isEqualTo(1);
      assertThat(dsl.selectCount().from(LEDGER_ENTRIES).fetchOne(count())).isEqualTo(2);

      ResponseEntity<Map> aliceBalance =
          rest.exchange(
              url("/api/v1/accounts/" + aliceId + "/balance"),
              HttpMethod.GET,
              authed(null),
              Map.class);
      assertThat(((Number) aliceBalance.getBody().get("balance")).longValue()).isEqualTo(9_000L);
    } finally {
      executor.shutdown();
    }
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
