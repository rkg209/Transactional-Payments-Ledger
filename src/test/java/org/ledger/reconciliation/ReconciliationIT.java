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
 * SPEC 0005 — accounts funded <i>through</i> genesis transfers, and further transfers between them,
 * must reconcile with {@code balance == Σentries} exactly for every account genesis funded. Genesis
 * itself is the one account this cannot be true for: it is seeded with external, entry-less capital
 * (same rationale as {@code seedInitialBalance}), so it always shows up as the single, known,
 * fixed-magnitude drift, regardless of how much has been transferred out of it (ADR 0007 decision 5
 * — {@code accounts_min_balance_gte_zero} rules out a truly zero-drift genesis under this schema).
 */
class ReconciliationIT extends AbstractPostgresIT {

  @Autowired private AccountService accountService;
  @Autowired private ReconciliationService reconciliationService;

  @Test
  void genesisFundedTransfersReconcileCleanExceptForTheKnownGenesisSeed() {
    UUID genesis = createGenesisAccount("USD");
    AccountResult alice =
        accountService.createAccount("recon-alice-" + UUID.randomUUID(), "USD", 0);
    AccountResult bob = accountService.createAccount("recon-bob-" + UUID.randomUUID(), "USD", 0);

    fundFromGenesis(genesis, alice.id(), 10_000L, "USD");
    fundFromGenesis(genesis, bob.id(), 5_000L, "USD");
    transferService.execute(alice.id(), bob.id(), 2_500L, "USD", UUID.randomUUID().toString());

    ReconciliationReport report = reconciliationService.runCheck();

    // Every transfer's entries cancel pairwise, regardless of any account's seeded starting
    // capital, so the global sum is 0 even though genesis itself is expected to show drift.
    assertThat(report.globalSum()).isZero();
    assertThat(report.driftDetected()).isTrue();
    assertThat(report.accountsDrifted()).isEqualTo(1);
    assertThat(report.accountsChecked()).isEqualTo(3); // genesis + alice + bob

    AccountDrift genesisDrift = report.details().get(0);
    assertThat(genesisDrift.accountId()).isEqualTo(genesis);
    assertThat(genesisDrift.drift()).isEqualTo(GENESIS_STARTING_CAPITAL);

    int reportRows = dsl.fetchCount(DSL.table("reconciliation_reports"));
    assertThat(reportRows).isEqualTo(1);

    Boolean driftDetailsIsNotNull =
        dsl.fetchOne(
                "SELECT drift_details IS NOT NULL FROM reconciliation_reports WHERE id = ?",
                report.id())
            .get(0, Boolean.class);
    assertThat(driftDetailsIsNotNull).isTrue();
  }
}
