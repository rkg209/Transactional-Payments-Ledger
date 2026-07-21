package org.ledger.concurrency;

import java.util.UUID;
import org.ledger.db.AccountRepository;
import org.ledger.db.generated.tables.records.AccountsRecord;

/**
 * Plain read, then {@code UPDATE ... WHERE id = ? AND version = ?}. No row lock is ever held; the
 * version compare-and-set is what detects — and rejects — a lost update.
 */
public class OptimisticStrategy implements ConcurrencyStrategy {

  private final AccountRepository accountRepository;

  public OptimisticStrategy(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  @Override
  public LockedAccounts lockAndLoad(UUID a, UUID b) {
    return LockedAccounts.of(a, b, accountRepository.findOrdered(a, b));
  }

  @Override
  public void applyDelta(LockedAccounts accounts, UUID accountId, long delta) {
    AccountsRecord account = accounts.byId(accountId);
    int rowsUpdated = accountRepository.applyDeltaIfVersion(accountId, delta, account.getVersion());
    if (rowsUpdated == 0) {
      throw new ConcurrencyConflictException(accountId);
    }
  }

  @Override
  public String name() {
    return "optimistic";
  }
}
