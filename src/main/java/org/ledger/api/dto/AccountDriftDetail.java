package org.ledger.api.dto;

import java.util.UUID;
import org.ledger.reconciliation.AccountDrift;

public record AccountDriftDetail(
    UUID accountId, long materializedBalance, long entrySum, long drift) {

  public static AccountDriftDetail from(AccountDrift drift) {
    return new AccountDriftDetail(
        drift.accountId(), drift.materializedBalance(), drift.entrySum(), drift.drift());
  }
}
