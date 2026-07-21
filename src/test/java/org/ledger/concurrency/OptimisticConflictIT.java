package org.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.db.AccountRepository;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the exact compare-and-set mechanism the retry loop depends on, deterministically rather
 * than by racing threads against real transaction timing: read a version, let a competing write
 * commit against that same version, then try to apply against the now-stale version and assert it
 * is rejected and leaves no trace (SPEC 0004, ADR 0006).
 *
 * <p>Every repository call below is wrapped in {@code tx.executeWithoutResult}/{@code tx.execute}
 * explicitly: {@code AccountRepository} and {@code OptimisticStrategy} carry no transaction
 * boundary of their own (that is {@code TransferService}'s job on the real write path), and with
 * {@code spring.datasource.hikari.auto-commit=false} project-wide, an unwrapped write here would
 * appear to succeed on its own connection and then silently roll back when that connection returns
 * to the pool -- the exact bug documented in progress_report.md's SPEC 0003 entry.
 */
@TestPropertySource(properties = "ledger.concurrency-strategy=optimistic")
class OptimisticConflictIT extends AbstractPostgresIT {

  @Autowired private AccountService accountService;
  @Autowired private AccountRepository accountRepository;
  @Autowired private OptimisticStrategy optimisticStrategy;

  @Test
  void staleVersionCompareAndSetIsRejectedAndAppliesNothing() {
    AccountResult account = accountService.createAccount("conflict-" + UUID.randomUUID(), "USD", 0);
    seedInitialBalance(account.id(), 1_000L);

    LockedAccounts staleRead =
        tx.execute(status -> optimisticStrategy.lockAndLoad(account.id(), account.id()));
    long staleVersion = staleRead.from().getVersion();

    // A competing writer commits against that same version first.
    int competingRowsUpdated =
        tx.execute(
            status -> accountRepository.applyDeltaIfVersion(account.id(), -100L, staleVersion));
    assertThat(competingRowsUpdated).isEqualTo(1);
    assertThat(accountService.getAccount(account.id()).balance()).isEqualTo(900L);
    long newVersion = staleVersion + 1;
    assertThat(accountService.getAccount(account.id()).version()).isEqualTo(newVersion);

    // The stale read's own attempt to apply now loses the race.
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> optimisticStrategy.applyDelta(staleRead, account.id(), -100L)))
        .isInstanceOf(ConcurrencyConflictException.class);

    // Nothing from the rejected attempt was applied: balance and version are exactly what the
    // competing writer alone produced.
    assertThat(accountService.getAccount(account.id()).balance()).isEqualTo(900L);
    assertThat(accountService.getAccount(account.id()).version()).isEqualTo(newVersion);
  }
}
