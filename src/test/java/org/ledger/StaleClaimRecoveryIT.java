package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ledger.db.generated.tables.IdempotencyKeys.IDEMPOTENCY_KEYS;
import static org.ledger.db.generated.tables.LedgerEntries.LEDGER_ENTRIES;
import static org.ledger.db.generated.tables.Transfers.TRANSFERS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.db.IdempotencyKeyRepository;
import org.ledger.support.AbstractApiIT;
import org.ledger.transfer.TransferService;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * SPEC 0010 — a key stranded {@code IN_PROGRESS} by a process that died between its claim and its
 * terminal status write must stay completable. Before this spec it never was: every retry polled,
 * timed out, and got {@code 503 IDEMPOTENCY_TIMEOUT} forever, so one specific payment could never
 * be made again (ADR 0005 named the hole and deferred it; ADR 0012 closes it).
 *
 * <p>A JVM kill cannot be staged inside one in-process test, so the crash is simulated at the exact
 * observable boundary that matters: {@code IdempotencyKeyRepository}'s terminal write is suppressed
 * once, leaving behind precisely the row a killed process leaves — {@code IN_PROGRESS}, with a real
 * fingerprint computed by the real filter from the real request bytes. That fidelity is the point:
 * a hand-written claim row would need a hand-computed fingerprint, and if it disagreed with what
 * the filter computes, the retry would be rejected as key reuse and never reach the code under test
 * at all — the test would pass for the wrong reason.
 *
 * <p>Both crash windows are covered, because they fail differently:
 *
 * <ul>
 *   <li><b>Before the transfer committed</b> — the reclaim must re-execute it for real.
 *   <li><b>After the transfer committed</b> — the reclaim must NOT re-post it. {@code
 *       TransferService}'s pre-insert lookup returns the committed row; without that, the re-insert
 *       would violate {@code transfers_idempotency_key_uq} and 500 on every retry forever.
 * </ul>
 *
 * <p>{@code updated_at} is backdated rather than slept through, and the threshold is lowered to 2 s
 * so the "fresh claim is not reclaimed" case still has a meaningful window after the filter's own 1
 * s poll without making the suite slow.
 */
@TestPropertySource(properties = "ledger.idempotency.stale-claim-after-ms=2000")
class StaleClaimRecoveryIT extends AbstractApiIT {

  @SpyBean private IdempotencyKeyRepository idempotencyKeyRepository;
  @SpyBean private TransferService transferService;

  @Test
  void staleClaimIsReclaimedAndTheTransferExecutesWhenItNeverCommitted() {
    UUID alice = createAccount("alice-stale-precommit");
    UUID bob = createAccount("bob-stale-precommit");
    seedInitialBalance(alice, 10_000L);
    String key = "stale-precommit-" + UUID.randomUUID();
    Map<String, Object> request = transferRequest(alice, bob, 1_000);

    // Crash BEFORE the transfer commits: the transfer blows up, and the filter's markFailed --
    // the thing that would normally make the key retryable -- never lands either.
    doThrow(new RuntimeException("simulated crash before commit"))
        .doCallRealMethod()
        .when(transferService)
        .execute(any(), any(), anyLong(), anyString(), anyString());
    doNothing().doCallRealMethod().when(idempotencyKeyRepository).markFailed(anyString());

    post(request, key);
    assertThat(statusOf(key)).isEqualTo("IN_PROGRESS");
    assertThat(transferCountFor(key)).isZero();

    ageClaim(key, Duration.ofSeconds(10));
    ResponseEntity<Map> retry = post(request, key);

    // The whole point: a real 201, not the 503 this used to return forever.
    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(statusOf(key)).isEqualTo("COMPLETED");
    assertThat(transferCountFor(key)).isEqualTo(1);
    assertThat(entryCountFor(key)).isEqualTo(2);
    assertGlobalSumIsZero();
  }

  @Test
  void staleClaimWhoseTransferAlreadyCommittedIsNotRePosted() {
    UUID alice = createAccount("alice-stale-postcommit");
    UUID bob = createAccount("bob-stale-postcommit");
    seedInitialBalance(alice, 10_000L);
    String key = "stale-postcommit-" + UUID.randomUUID();
    Map<String, Object> request = transferRequest(alice, bob, 1_000);

    // Crash AFTER the transfer commits, before the key is marked COMPLETED -- the narrow window
    // ADR 0005 called out. The transfer is real and durable; only the bookkeeping is missing.
    doNothing()
        .doCallRealMethod()
        .when(idempotencyKeyRepository)
        .markCompleted(anyString(), anyString());

    post(request, key);
    assertThat(statusOf(key)).isEqualTo("IN_PROGRESS");
    assertThat(transferCountFor(key)).isEqualTo(1);

    ageClaim(key, Duration.ofSeconds(10));
    ResponseEntity<Map> retry = post(request, key);

    assertThat(retry.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(statusOf(key)).isEqualTo("COMPLETED");
    // Still exactly one. A second insert under the same key is what we are proving cannot happen.
    assertThat(transferCountFor(key)).isEqualTo(1);
    assertThat(entryCountFor(key)).isEqualTo(2);
    assertGlobalSumIsZero();
  }

  @Test
  void freshClaimIsNotReclaimedAndStillTimesOut() {
    UUID alice = createAccount("alice-fresh");
    UUID bob = createAccount("bob-fresh");
    seedInitialBalance(alice, 10_000L);
    String key = "fresh-" + UUID.randomUUID();
    Map<String, Object> request = transferRequest(alice, bob, 1_000);

    doNothing()
        .doCallRealMethod()
        .when(idempotencyKeyRepository)
        .markCompleted(anyString(), anyString());

    post(request, key);
    assertThat(statusOf(key)).isEqualTo("IN_PROGRESS");

    // NOT aged. A genuinely in-flight request must never be stolen out from under itself, so the
    // caller still gets the old behavior: poll, then 503.
    ResponseEntity<Map> retry = post(request, key);

    assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(statusOf(key)).isEqualTo("IN_PROGRESS");
    assertThat(transferCountFor(key)).isEqualTo(1);
    assertGlobalSumIsZero();
  }

  @Test
  void concurrentRetriesAgainstOneStaleClaimApplyItExactlyOnce() throws Exception {
    UUID alice = createAccount("alice-stale-race");
    UUID bob = createAccount("bob-stale-race");
    seedInitialBalance(alice, 10_000L);
    String key = "stale-race-" + UUID.randomUUID();
    Map<String, Object> request = transferRequest(alice, bob, 1_000);

    doThrow(new RuntimeException("simulated crash before commit"))
        .doCallRealMethod()
        .when(transferService)
        .execute(any(), any(), anyLong(), anyString(), anyString());
    doNothing().doCallRealMethod().when(idempotencyKeyRepository).markFailed(anyString());

    post(request, key);
    assertThat(statusOf(key)).isEqualTo("IN_PROGRESS");
    assertThat(transferCountFor(key)).isZero();

    ageClaim(key, Duration.ofSeconds(10));

    // Two retries released together. reclaimStale is guarded by WHERE status = 'IN_PROGRESS' AND
    // updated_at < cutoff, so exactly one UPDATE can match; the loser must not double-apply.
    CyclicBarrier gate = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<ResponseEntity<Map>>> results =
          List.of(
              pool.submit(() -> awaitThenPost(gate, request, key)),
              pool.submit(() -> awaitThenPost(gate, request, key)));
      for (Future<ResponseEntity<Map>> result : results) {
        assertThat(result.get().getStatusCode().isError()).isFalse();
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(transferCountFor(key)).isEqualTo(1);
    assertThat(entryCountFor(key)).isEqualTo(2);
    assertGlobalSumIsZero();
  }

  // ---------------------------------------------------------------------------------------------

  /** Backdates the claim so it is past the staleness threshold without the test sleeping for it. */
  private void ageClaim(String key, Duration age) {
    tx.executeWithoutResult(
        s ->
            dsl.update(IDEMPOTENCY_KEYS)
                .set(IDEMPOTENCY_KEYS.UPDATED_AT, OffsetDateTime.now().minus(age))
                .where(IDEMPOTENCY_KEYS.KEY.eq(key))
                .execute());
  }

  private void assertGlobalSumIsZero() {
    Long sum =
        dsl.select(
                DSL.coalesce(
                    DSL.sum(
                        DSL.when(LEDGER_ENTRIES.DIRECTION.eq("CREDIT"), LEDGER_ENTRIES.AMOUNT_MINOR)
                            .otherwise(LEDGER_ENTRIES.AMOUNT_MINOR.neg())),
                    java.math.BigDecimal.ZERO))
            .from(LEDGER_ENTRIES)
            .fetchOne(0, Long.class);
    assertThat(sum).isZero();
  }

  private ResponseEntity<Map> awaitThenPost(
      CyclicBarrier gate, Map<String, Object> request, String key) throws Exception {
    gate.await();
    return post(request, key);
  }

  private ResponseEntity<Map> post(Map<String, Object> request, String key) {
    return rest.exchange(
        url("/api/v1/transfers"),
        HttpMethod.POST,
        authedWithIdempotencyKey(request, key),
        Map.class);
  }

  private Map<String, Object> transferRequest(UUID from, UUID to, long amount) {
    return Map.of(
        "fromAccountId",
        from.toString(),
        "toAccountId",
        to.toString(),
        "amount",
        amount,
        "currency",
        "USD");
  }

  private UUID createAccount(String name) {
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(
                Map.of("name", name + "-" + UUID.randomUUID(), "currency", "USD"),
                UUID.randomUUID().toString()),
            Map.class);
    return UUID.fromString((String) response.getBody().get("id"));
  }

  private String statusOf(String key) {
    return dsl.select(IDEMPOTENCY_KEYS.STATUS)
        .from(IDEMPOTENCY_KEYS)
        .where(IDEMPOTENCY_KEYS.KEY.eq(key))
        .fetchOne(IDEMPOTENCY_KEYS.STATUS);
  }

  private int transferCountFor(String key) {
    return dsl.fetchCount(TRANSFERS, TRANSFERS.IDEMPOTENCY_KEY.eq(key));
  }

  private int entryCountFor(String key) {
    return dsl.fetchCount(
        LEDGER_ENTRIES,
        LEDGER_ENTRIES.TRANSFER_ID.in(
            dsl.select(TRANSFERS.ID).from(TRANSFERS).where(TRANSFERS.IDEMPOTENCY_KEY.eq(key))));
  }
}
