package org.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.support.AbstractPostgresIT;
import org.ledger.transfer.InsufficientFundsException;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An account seeded with exactly enough for one withdrawal, hit by 20 concurrent attempts: exactly
 * one must win, the rest must fail with {@link InsufficientFundsException} — never as a database
 * CHECK-constraint violation surfacing as a 500 (SPEC 0004).
 */
abstract class AbstractNegativeBalanceIT extends AbstractPostgresIT {

  private static final int ATTEMPTS = 20;
  private static final long AMOUNT = 100L;

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;
  @Autowired private LedgerEntryRepository ledgerEntryRepository;

  @Test
  void exactlyOneOfManyConcurrentWithdrawalsSucceeds() throws Exception {
    AccountResult source =
        accountService.createAccount("negbal-source-" + UUID.randomUUID(), "USD", 0);
    AccountResult sink = accountService.createAccount("negbal-sink-" + UUID.randomUUID(), "USD", 0);
    seedAccountState(source.id(), AMOUNT, 0);

    CountDownLatch startGate = new CountDownLatch(1);
    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger insufficientFunds = new AtomicInteger();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < ATTEMPTS; i++) {
        Callable<Void> task =
            () -> {
              awaitUninterruptibly(startGate);
              try {
                transferService.execute(
                    source.id(), sink.id(), AMOUNT, "USD", "negbal-" + UUID.randomUUID());
                succeeded.incrementAndGet();
              } catch (InsufficientFundsException e) {
                insufficientFunds.incrementAndGet();
              }
              return null;
            };
        futures.add(executor.submit(task));
      }
      startGate.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      executor.shutdown();
    }

    assertThat(succeeded.get()).isEqualTo(1);
    assertThat(insufficientFunds.get()).isEqualTo(ATTEMPTS - 1);
    assertThat(accountService.getAccount(source.id()).balance()).isGreaterThanOrEqualTo(0L);
    assertThat(ledgerEntryRepository.globalEntrySum()).isZero();
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
