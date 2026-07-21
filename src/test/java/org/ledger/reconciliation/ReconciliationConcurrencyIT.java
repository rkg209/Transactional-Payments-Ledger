package org.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SPEC 0004's hammer (32 threads x 25 transfers, all into one hot account), genesis-funded so the
 * production checker rather than bespoke test SQL gets to make the claim: the sentence this whole
 * project exists to earn, asserted by {@link ReconciliationService#runCheck()} itself.
 */
class ReconciliationConcurrencyIT extends AbstractPostgresIT {

  private static final int THREADS = 32;
  private static final int TRANSFERS_PER_THREAD = 25;
  private static final long AMOUNT = 10L;

  @Autowired private AccountService accountService;
  @Autowired private ReconciliationService reconciliationService;

  @Test
  void hammeredHotAccountStillReconcilesClean() throws Exception {
    UUID genesis = createGenesisAccount("USD");
    AccountResult source =
        accountService.createAccount("recon-hammer-source-" + UUID.randomUUID(), "USD", 0);
    AccountResult hot =
        accountService.createAccount("recon-hammer-hot-" + UUID.randomUUID(), "USD", 0);
    fundFromGenesis(genesis, source.id(), (long) THREADS * TRANSFERS_PER_THREAD * AMOUNT, "USD");

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int t = 0; t < THREADS; t++) {
        futures.add(
            executor.submit(
                () -> {
                  awaitUninterruptibly(startGate);
                  for (int i = 0; i < TRANSFERS_PER_THREAD; i++) {
                    transferService.execute(
                        source.id(), hot.id(), AMOUNT, "USD", "recon-hammer-" + UUID.randomUUID());
                  }
                }));
      }
      startGate.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      executor.shutdown();
    }

    ReconciliationReport report = reconciliationService.runCheck();

    // Only genesis's own known, constant seed drifts (ADR 0007 decision 5); source and hot must
    // not, however hard they were hammered concurrently.
    assertThat(report.globalSum()).isZero();
    assertThat(report.accountsDrifted()).isEqualTo(1);
    assertThat(report.details().get(0).accountId()).isEqualTo(genesis);
    assertThat(report.details().get(0).drift()).isEqualTo(GENESIS_STARTING_CAPITAL);
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
