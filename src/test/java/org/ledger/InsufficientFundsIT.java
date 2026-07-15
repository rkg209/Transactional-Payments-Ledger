package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.support.AbstractPostgresIT;
import org.ledger.transfer.InsufficientFundsException;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

class InsufficientFundsIT extends AbstractPostgresIT {

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;

  @Test
  void overdraftIsRejectedAndNothingIsWritten() {
    AccountResult from = accountService.createAccount("broke", "USD", 0);
    AccountResult to = accountService.createAccount("rich", "USD", 0);

    assertThatThrownBy(() -> transferService.execute(from.id(), to.id(), 500L, "USD"))
        .isInstanceOf(InsufficientFundsException.class);

    assertThat(accountService.getAccount(from.id()).balance()).isZero();
    assertThat(accountService.getAccount(to.id()).balance()).isZero();
    assertThat(dsl.fetchCount(DSL.table("transfers"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("ledger_entries"))).isZero();
  }

  @Test
  void transferThatWouldBreachMinBalanceIsRejected() {
    AccountResult from = accountService.createAccount("checking", "USD", 0);
    AccountResult to = accountService.createAccount("savings", "USD", 0);
    seedAccountState(from.id(), 150L, 100L);

    // Balance (150) - amount (100) = 50, which is below min_balance (100).
    assertThatThrownBy(() -> transferService.execute(from.id(), to.id(), 100L, "USD"))
        .isInstanceOf(InsufficientFundsException.class);

    assertThat(accountService.getAccount(from.id()).balance()).isEqualTo(150L);
    assertThat(accountService.getAccount(to.id()).balance()).isZero();
    assertThat(dsl.fetchCount(DSL.table("transfers"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("ledger_entries"))).isZero();
  }
}
