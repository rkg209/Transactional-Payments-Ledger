package org.ledger.concurrency;

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
import org.ledger.db.LedgerEntryRepository;
import org.ledger.support.AbstractPostgresIT;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SPEC 0004 — K threads x M transfers each, all into one hot account, driven off virtual threads
 * (application.yml:8-10) with a {@link CountDownLatch} start gate so every attempt actually races.
 * Concrete subclasses select the strategy via {@code @TestPropertySource}, proving the startup
 * toggle itself.
 */
abstract class AbstractConcurrencyHammerIT extends AbstractPostgresIT {

  private static final int THREADS = 32;
  private static final int TRANSFERS_PER_THREAD = 25;
  private static final long AMOUNT = 10L;
  private static final long SOURCE_START_BALANCE = THREADS * TRANSFERS_PER_THREAD * AMOUNT;

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;
  @Autowired private LedgerEntryRepository ledgerEntryRepository;

  @Test
  void manyConcurrentTransfersIntoOneHotAccountLoseNothing() throws Exception {
    AccountResult source =
        accountService.createAccount("hammer-source-" + UUID.randomUUID(), "USD", 0);
    AccountResult hot = accountService.createAccount("hammer-hot-" + UUID.randomUUID(), "USD", 0);
    seedInitialBalance(source.id(), SOURCE_START_BALANCE);

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
                        source.id(), hot.id(), AMOUNT, "USD", "hammer-" + UUID.randomUUID());
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

    long totalMoved = (long) THREADS * TRANSFERS_PER_THREAD * AMOUNT;
    assertThat(accountService.getAccount(source.id()).balance())
        .isEqualTo(SOURCE_START_BALANCE - totalMoved);
    assertThat(accountService.getAccount(hot.id()).balance()).isEqualTo(totalMoved);
    assertThat(ledgerEntryRepository.globalEntrySum()).isZero();
    assertThat(ledgerEntryRepository.entrySumForAccount(hot.id())).isEqualTo(totalMoved);
    assertThat(ledgerEntryRepository.entrySumForAccount(source.id())).isEqualTo(-totalMoved);
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
