package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.support.AbstractPostgresIT;
import org.ledger.transfer.InsufficientFundsException;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * N random transfers over M accounts. Seeded {@link Random}, not a property-testing library (jqwik)
 * -- no new test-generator dependency for SPEC 0001. After every attempted transfer, whether it
 * succeeds or is correctly rejected as an overdraft, conservation and non-negativity must hold.
 */
class DoubleEntryPropertyIT extends AbstractPostgresIT {

  private static final int ACCOUNT_COUNT = 8;
  private static final int TRANSFER_COUNT = 200;
  private static final long SEED_BALANCE = 100_000L;

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;
  @Autowired private LedgerEntryRepository ledgerEntryRepository;

  @Test
  void randomTransferScheduleNeverViolatesConservationOrNonNegativity() {
    Random random = new Random(42);

    List<AccountResult> accounts = new ArrayList<>();
    for (int i = 0; i < ACCOUNT_COUNT; i++) {
      AccountResult account = accountService.createAccount("acct-" + i, "USD", 0);
      seedInitialBalance(account.id(), SEED_BALANCE);
      accounts.add(account);
    }

    long totalBefore = (long) accounts.size() * SEED_BALANCE;

    for (int i = 0; i < TRANSFER_COUNT; i++) {
      AccountResult from = accounts.get(random.nextInt(ACCOUNT_COUNT));
      AccountResult to = accounts.get(random.nextInt(ACCOUNT_COUNT));
      if (from.id().equals(to.id())) {
        continue;
      }
      long amount = 1 + random.nextInt(50_000);

      try {
        transferService.execute(from.id(), to.id(), amount, "USD", "property-it-key-" + i);
      } catch (InsufficientFundsException expected) {
        // Overdraft correctly rejected -- verified below that state is untouched.
      }

      assertThat(ledgerEntryRepository.globalEntrySum()).isZero();

      long total = 0;
      for (AccountResult seedAccount : accounts) {
        AccountResult current = accountService.getAccount(seedAccount.id());
        assertThat(current.balance()).isGreaterThanOrEqualTo(0L);
        assertThat(current.balance())
            .isEqualTo(SEED_BALANCE + ledgerEntryRepository.entrySumForAccount(current.id()));
        total += current.balance();
      }
      assertThat(total).isEqualTo(totalBefore);
    }
  }
}
