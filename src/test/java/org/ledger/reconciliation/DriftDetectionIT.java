package org.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SPEC 0005's load-bearing test: the detector must actually detect, for both drift shapes the
 * invariant covers. Neither case may be "repaired" by the run -- {@code accounts.balance} and
 * {@code ledger_entries} must be exactly as corrupted/empty after the run as before it.
 */
class DriftDetectionIT extends AbstractPostgresIT {

  @Autowired private AccountService accountService;
  @Autowired private ReconciliationService reconciliationService;

  @Test
  void corruptedBalanceOnAnAccountWithEntriesIsDetectedAndNotRepaired() {
    UUID genesis = createGenesisAccount("USD");
    AccountResult alice =
        accountService.createAccount("drift-alice-" + UUID.randomUUID(), "USD", 0);
    fundFromGenesis(genesis, alice.id(), 10_000L, "USD");

    injectDrift(alice.id(), 100L);
    long corruptedBalance = accountService.getAccount(alice.id()).balance();
    int entriesBefore = ledgerEntryCount();

    ReconciliationReport report = reconciliationService.runCheck();

    // 2 drifted accounts: genesis (its known, constant seed) and alice (the injected corruption).
    assertThat(report.driftDetected()).isTrue();
    assertThat(report.accountsDrifted()).isEqualTo(2);
    AccountDrift drift =
        report.details().stream()
            .filter(d -> d.accountId().equals(alice.id()))
            .findFirst()
            .orElseThrow();
    assertThat(drift.materializedBalance()).isEqualTo(corruptedBalance);
    assertThat(drift.entrySum()).isEqualTo(10_000L);
    assertThat(drift.drift()).isEqualTo(100L);

    assertThat(accountService.getAccount(alice.id()).balance()).isEqualTo(corruptedBalance);
    assertThat(ledgerEntryCount()).isEqualTo(entriesBefore);

    Long jsonbContainsAccount =
        dsl.fetchOne(
                "SELECT (drift_details @> ?::jsonb)::int FROM reconciliation_reports WHERE id = ?",
                "[{\"accountId\":\"" + alice.id() + "\"}]",
                report.id())
            .get(0, Long.class);
    assertThat(jsonbContainsAccount).isEqualTo(1L);
  }

  @Test
  void nonZeroBalanceWithZeroEntriesIsDetectedTheInnerJoinWouldMiss() {
    // No genesis funding, no transfer at all -- an account with a balance and no matching entries,
    // the exact case an INNER JOIN over ledger_entries makes invisible (SPEC 0005 decision 1).
    AccountResult orphan =
        accountService.createAccount("drift-orphan-" + UUID.randomUUID(), "USD", 0);
    injectDrift(orphan.id(), 500L);
    int entriesBefore = ledgerEntryCount();

    ReconciliationReport report = reconciliationService.runCheck();

    assertThat(report.driftDetected()).isTrue();
    assertThat(report.accountsDrifted()).isEqualTo(1);
    AccountDrift drift =
        report.details().stream()
            .filter(d -> d.accountId().equals(orphan.id()))
            .findFirst()
            .orElseThrow();
    assertThat(drift.entrySum()).isZero();
    assertThat(drift.materializedBalance()).isEqualTo(500L);
    assertThat(drift.drift()).isEqualTo(500L);

    assertThat(accountService.getAccount(orphan.id()).balance()).isEqualTo(500L);
    assertThat(ledgerEntryCount()).isEqualTo(entriesBefore);
  }

  private int ledgerEntryCount() {
    return dsl.fetchCount(DSL.table("ledger_entries"));
  }
}
