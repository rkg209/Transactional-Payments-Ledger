package org.ledger.concurrency;

import java.util.UUID;
import org.ledger.db.AccountRepository;

/**
 * {@code SELECT ... FOR UPDATE}, both rows in one statement, ascending id order. The row lock held
 * for the rest of the transaction is what makes the later unconditional {@code applyDelta} safe.
 */
public class PessimisticStrategy implements ConcurrencyStrategy {

  private final AccountRepository accountRepository;

  public PessimisticStrategy(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  @Override
  public LockedAccounts lockAndLoad(UUID a, UUID b) {
    return LockedAccounts.of(a, b, accountRepository.findOrderedForUpdate(a, b));
  }

  @Override
  public void applyDelta(LockedAccounts accounts, UUID accountId, long delta) {
    accountRepository.applyDelta(accountId, delta);
  }

  @Override
  public String name() {
    return "pessimistic";
  }
}
