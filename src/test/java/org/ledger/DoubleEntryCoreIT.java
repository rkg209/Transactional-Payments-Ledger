package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.support.AbstractPostgresIT;
import org.ledger.transfer.TransferResult;
import org.ledger.transfer.TransferService;
import org.ledger.transfer.TransferStatus;
import org.springframework.beans.factory.annotation.Autowired;

class DoubleEntryCoreIT extends AbstractPostgresIT {

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;
  @Autowired private LedgerEntryRepository ledgerEntryRepository;

  @Test
  void balancedTransferMovesBothBalancesConservesMoneyAndSumsToZero() {
    AccountResult from = accountService.createAccount("alice", "USD", 0);
    AccountResult to = accountService.createAccount("bob", "USD", 0);
    seedInitialBalance(from.id(), 10_000L);

    long totalBefore =
        accountService.getAccount(from.id()).balance()
            + accountService.getAccount(to.id()).balance();

    TransferResult result =
        transferService.execute(from.id(), to.id(), 2_500L, "USD", "double-entry-core-it-key");

    assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);

    AccountResult fromAfter = accountService.getAccount(from.id());
    AccountResult toAfter = accountService.getAccount(to.id());

    assertThat(fromAfter.balance()).isEqualTo(10_000L - 2_500L);
    assertThat(toAfter.balance()).isEqualTo(2_500L);

    long totalAfter = fromAfter.balance() + toAfter.balance();
    assertThat(totalAfter).isEqualTo(totalBefore);

    assertThat(ledgerEntryRepository.globalEntrySum()).isZero();
    // 'to' received its balance solely through ledger entries -- no seed -- so its balance
    // equals its entry sum exactly.
    assertThat(toAfter.balance()).isEqualTo(ledgerEntryRepository.entrySumForAccount(to.id()));

    int entryCount =
        dsl.fetchCount(
            DSL.table("ledger_entries"),
            DSL.field("transfer_id", UUID.class).eq(result.transferId()));
    assertThat(entryCount).isEqualTo(2);
  }
}
