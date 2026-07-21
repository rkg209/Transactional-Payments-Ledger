package org.ledger.reconciliation;

import java.util.UUID;
import org.ledger.db.AccountDriftRow;

/** {@code drift} is {@code materializedBalance - entrySum}; non-zero means it drifted. */
public record AccountDrift(UUID accountId, long materializedBalance, long entrySum, long drift) {

  public static AccountDrift from(AccountDriftRow row) {
    return new AccountDrift(
        row.accountId(),
        row.materializedBalance(),
        row.entrySum(),
        row.materializedBalance() - row.entrySum());
  }
}
