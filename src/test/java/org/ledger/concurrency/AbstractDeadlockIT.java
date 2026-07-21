package org.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.support.AbstractPostgresIT;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * N threads doing A->B interleaved with N doing B->A. Both strategies lock rows in a single
 * ascending-id statement (ADR 0006), so this must complete inside a hard timeout rather than
 * deadlock the database.
 */
abstract class AbstractDeadlockIT extends AbstractPostgresIT {

  private static final int THREADS_PER_DIRECTION = 16;
  private static final long AMOUNT = 5L;
  private static final long START_BALANCE = 100_000L;

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;

  @Test
  @Timeout(60)
  void bidirectionalTransfersCompleteWithoutDeadlock() throws Exception {
    AccountResult a = accountService.createAccount("deadlock-a-" + UUID.randomUUID(), "USD", 0);
    AccountResult b = accountService.createAccount("deadlock-b-" + UUID.randomUUID(), "USD", 0);
    seedInitialBalance(a.id(), START_BALANCE);
    seedInitialBalance(b.id(), START_BALANCE);

    CountDownLatch startGate = new CountDownLatch(1);
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < THREADS_PER_DIRECTION; i++) {
        futures.add(
            executor.submit(
                () -> {
                  awaitUninterruptibly(startGate);
                  transferService.execute(
                      a.id(), b.id(), AMOUNT, "USD", "deadlock-ab-" + UUID.randomUUID());
                }));
        futures.add(
            executor.submit(
                () -> {
                  awaitUninterruptibly(startGate);
                  transferService.execute(
                      b.id(), a.id(), AMOUNT, "USD", "deadlock-ba-" + UUID.randomUUID());
                }));
      }
      startGate.countDown();
      for (Future<?> f : futures) {
        f.get(55, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdown();
    }

    assertThat(accountService.getAccount(a.id()).balance()).isEqualTo(START_BALANCE);
    assertThat(accountService.getAccount(b.id()).balance()).isEqualTo(START_BALANCE);
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
