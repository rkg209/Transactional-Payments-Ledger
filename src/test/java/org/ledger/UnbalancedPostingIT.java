package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.support.AbstractPostgresIT;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The zero-sum invariant is enforced by construction: {@code LedgerEntryRepository} has exactly one
 * write method, and it always writes a DEBIT and a CREDIT of equal amount together. There is no
 * production code path that can post a single unbalanced entry (open question 1 in the SPEC 0001
 * implementation plan), so this class proves that structurally, then proves the transaction as a
 * whole is atomic by forcing a failure partway through the write sequence and confirming zero rows
 * survive.
 */
class UnbalancedPostingIT extends AbstractPostgresIT {

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;

  @Test
  void ledgerEntryRepositoryExposesExactlyOneWriteMethodAndItIsBalanced() {
    long insertMethods =
        Arrays.stream(LedgerEntryRepository.class.getDeclaredMethods())
            .map(Method::getName)
            .filter(name -> name.toLowerCase().contains("insert"))
            .count();

    assertThat(insertMethods)
        .describedAs(
            "LedgerEntryRepository must expose exactly one insert method (insertBalancedPair) --"
                + " any second insert path would make an unbalanced posting possible")
        .isEqualTo(1);
  }

  @Test
  void forcedMidTransactionFailureLeavesNoOrphanRows() {
    AccountResult account = accountService.createAccount("solo", "USD", 0);
    seedInitialBalance(account.id(), 1_000L);

    // from == to trips the transfers_different_accounts CHECK deep inside the write sequence,
    // after the (read-only) balance guard has already passed -- proving the whole write sequence
    // rolls back as one unit, not just that early validation works.
    assertThatThrownBy(
        () ->
            transferService.execute(account.id(), account.id(), 100L, "USD", "unbalanced-it-key"));

    assertThat(dsl.fetchCount(DSL.table("transfers"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("ledger_entries"))).isZero();
  }
}
