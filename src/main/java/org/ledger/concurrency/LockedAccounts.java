package org.ledger.concurrency;

import java.util.List;
import java.util.UUID;
import org.ledger.db.generated.tables.records.AccountsRecord;

/**
 * The two account rows loaded by {@link ConcurrencyStrategy#lockAndLoad}, holding the versions read
 * so {@link ConcurrencyStrategy#applyDelta} can compare-and-set without a second read. Either slot
 * is {@code null} when that account id does not exist — the caller decides how to react.
 */
public record LockedAccounts(AccountsRecord from, AccountsRecord to) {

  /**
   * Builds a {@link LockedAccounts} from a same-statement fetch, matching rows back to {@code
   * a}/{@code b} by id.
   */
  static LockedAccounts of(UUID a, UUID b, List<AccountsRecord> rows) {
    AccountsRecord fromRow = null;
    AccountsRecord toRow = null;
    for (AccountsRecord row : rows) {
      if (row.getId().equals(a)) {
        fromRow = row;
      } else if (row.getId().equals(b)) {
        toRow = row;
      }
    }
    return new LockedAccounts(fromRow, toRow);
  }

  AccountsRecord byId(UUID accountId) {
    if (from != null && from.getId().equals(accountId)) {
      return from;
    }
    if (to != null && to.getId().equals(accountId)) {
      return to;
    }
    throw new IllegalArgumentException("Account " + accountId + " is not part of this lock set");
  }
}
