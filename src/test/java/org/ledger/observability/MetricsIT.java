package org.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountService;
import org.ledger.reconciliation.ReconciliationService;
import org.ledger.saga.SagaCompensatedException;
import org.ledger.saga.SagaDefinition;
import org.ledger.saga.SagaLeg;
import org.ledger.saga.SagaOrchestrator;
import org.ledger.support.AbstractApiIT;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;

/**
 * SPEC 0009 (NFR-20): drives one of each named event -- a plain transfer, a forced optimistic
 * conflict, a completed saga, a compensated saga, and a reconciliation run -- then asserts every
 * meter both exists on the real {@code /actuator/prometheus} scrape and that its value actually
 * moved, not just that the name was registered at startup.
 *
 * <p>{@code @AutoConfigureObservability} is required here: {@code @SpringBootTest} disables metrics
 * export by default (a test-only Boot behavior, {@code DisableObservabilityContextCustomizer}),
 * which otherwise silences {@code PrometheusMetricsExportAutoConfiguration} and 404s {@code
 * /actuator/prometheus} in every test context regardless of {@code
 * management.endpoints.web.exposure.include} -- production and `docker compose up` are unaffected,
 * since that customizer only applies to tests.
 */
@AutoConfigureObservability
@TestPropertySource(properties = "ledger.concurrency-strategy=optimistic")
class MetricsIT extends AbstractApiIT {

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;
  @Autowired private SagaOrchestrator sagaOrchestrator;
  @Autowired private ReconciliationService reconciliationService;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void everyNamedMeterIsPresentAndMoves() throws Exception {
    double transfersSuccessBefore = counterValue("ledger.transfers.total", "outcome", "success");
    double conflictsBefore =
        counterValue("ledger.transfer.conflicts.total", "strategy", "optimistic");
    long transferDurationCountBefore = timerCount("ledger.transfer.duration");
    double sagasCompletedBefore = counterValue("ledger.sagas.total", "outcome", "completed");
    double sagasCompensatedBefore = counterValue("ledger.sagas.total", "outcome", "compensated");
    double runsBefore = counterValue("reconciliation.runs.total", null, null);

    // 1. A plain transfer -- ledger.transfers.total{outcome=success}, ledger.transfer.duration.
    UUID a = accountService.createAccount("metrics-a-" + UUID.randomUUID(), "USD", 0).id();
    UUID b = accountService.createAccount("metrics-b-" + UUID.randomUUID(), "USD", 0).id();
    seedInitialBalance(a, 100_000L);
    transferService.execute(a, b, 1_000L, "USD", "metrics-plain-" + UUID.randomUUID());

    // 2. A forced conflict -- many threads debit the SAME hot account at once under the
    // optimistic strategy, so several race on the same version and must retry
    // (ledger.transfer.conflicts.total): only one CAS update can win per version, and Postgres's
    // own row lock serializes the competing UPDATEs, so with this much concurrency on one row at
    // least one loser -- and therefore one retry -- is not a matter of luck.
    UUID hot = accountService.createAccount("metrics-hot-" + UUID.randomUUID(), "USD", 0).id();
    seedInitialBalance(hot, 1_000_000L);
    int concurrency = 24;
    List<UUID> sinks =
        IntStream.range(0, concurrency)
            .mapToObj(
                i ->
                    accountService
                        .createAccount("metrics-sink-" + i + "-" + UUID.randomUUID(), "USD", 0)
                        .id())
            .toList();
    ExecutorService pool = Executors.newFixedThreadPool(concurrency);
    CountDownLatch ready = new CountDownLatch(concurrency);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (UUID sink : sinks) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  awaitUninterruptibly(go);
                  try {
                    transferService.execute(
                        hot, sink, 10L, "USD", "metrics-conflict-" + UUID.randomUUID());
                  } catch (RuntimeException e) {
                    // Retry-exhaustion under this much contention on one row is an expected,
                    // acceptable outcome here -- the point of this block is the conflict counter
                    // moving, not every racer winning (SPEC 0004's own retry budget is exercised
                    // separately by the harness and OptimisticConflictIT).
                  }
                }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdown();
    }

    // 3. A completed saga -- ledger.sagas.total{outcome=completed}.
    UUID sagaA = accountService.createAccount("metrics-saga-a-" + UUID.randomUUID(), "USD", 0).id();
    UUID sagaB = accountService.createAccount("metrics-saga-b-" + UUID.randomUUID(), "USD", 0).id();
    seedInitialBalance(sagaA, 10_000L);
    sagaOrchestrator.execute(
        new SagaDefinition(
            SagaDefinition.TYPE_MULTI_LEG_TRANSFER,
            "USD",
            null,
            List.of(new SagaLeg(0, sagaA, sagaB, 500L))));

    // 4. A compensated saga -- ledger.sagas.total{outcome=compensated}.
    UUID compA = accountService.createAccount("metrics-comp-a-" + UUID.randomUUID(), "USD", 0).id();
    UUID compB = accountService.createAccount("metrics-comp-b-" + UUID.randomUUID(), "USD", 0).id();
    UUID compC = accountService.createAccount("metrics-comp-c-" + UUID.randomUUID(), "USD", 0).id();
    seedInitialBalance(compA, 1_000L);
    catchThrowableOfType(
        () ->
            sagaOrchestrator.execute(
                new SagaDefinition(
                    SagaDefinition.TYPE_MULTI_LEG_TRANSFER,
                    "USD",
                    null,
                    List.of(
                        new SagaLeg(0, compA, compB, 1_000L),
                        new SagaLeg(1, compB, compC, 5_000L)))),
        SagaCompensatedException.class);

    // 5. A reconciliation run -- reconciliation.* meters.
    reconciliationService.runCheck();

    assertThat(counterValue("ledger.transfers.total", "outcome", "success"))
        .isGreaterThan(transfersSuccessBefore);
    assertThat(counterValue("ledger.transfer.conflicts.total", "strategy", "optimistic"))
        .isGreaterThan(conflictsBefore);
    assertThat(timerCount("ledger.transfer.duration")).isGreaterThan(transferDurationCountBefore);
    assertThat(counterValue("ledger.sagas.total", "outcome", "completed"))
        .isGreaterThan(sagasCompletedBefore);
    assertThat(counterValue("ledger.sagas.total", "outcome", "compensated"))
        .isGreaterThan(sagasCompensatedBefore);
    assertThat(counterValue("reconciliation.runs.total", null, null)).isGreaterThan(runsBefore);

    String scrape = rest.getForObject(url("/actuator/prometheus"), String.class);
    assertThat(scrape)
        .contains("ledger_transfers_total")
        .contains("outcome=\"success\"")
        .contains("ledger_transfer_conflicts_total")
        .contains("ledger_transfer_duration_seconds_count")
        .contains("ledger_sagas_total")
        .contains("outcome=\"completed\"")
        .contains("outcome=\"compensated\"")
        .contains("reconciliation_global_sum")
        .contains("reconciliation_status")
        .contains("reconciliation_runs_total")
        .contains("reconciliation_drift_count_total");
  }

  private double counterValue(String name, String tagKey, String tagValue) {
    Counter counter =
        tagKey == null
            ? meterRegistry.find(name).counter()
            : meterRegistry.find(name).tag(tagKey, tagValue).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private long timerCount(String name) {
    Timer timer = meterRegistry.find(name).timer();
    return timer == null ? 0L : timer.count();
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }
}
