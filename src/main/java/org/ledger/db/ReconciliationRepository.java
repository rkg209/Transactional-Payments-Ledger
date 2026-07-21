package org.ledger.db;

import static org.ledger.db.generated.tables.Accounts.ACCOUNTS;
import static org.ledger.db.generated.tables.ReconciliationReports.RECONCILIATION_REPORTS;

import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.ledger.db.generated.tables.records.ReconciliationReportsRecord;
import org.springframework.stereotype.Repository;

/**
 * Two reads and one insert — nothing here can write to {@code accounts} or {@code ledger_entries}
 * (CLAUDE.md invariant 5; decision 5 of SPEC 0005). {@link #findDriftedAccounts()} delegates to
 * {@link LedgerEntryRepository}, the only class permitted to reference the generated {@code
 * LedgerEntries} types ({@code ArchitectureFitnessTest.
 * only_ledger_entry_repository_touches_ledger_entries_generated_types}), which is also where the
 * single-statement snapshot argument for that query is documented.
 */
@Repository
public class ReconciliationRepository {

  private final DSLContext dsl;
  private final LedgerEntryRepository ledgerEntryRepository;

  public ReconciliationRepository(DSLContext dsl, LedgerEntryRepository ledgerEntryRepository) {
    this.dsl = dsl;
    this.ledgerEntryRepository = ledgerEntryRepository;
  }

  /** Signed sum across every entry ever posted. Must always be zero. */
  public long globalEntrySum() {
    return ledgerEntryRepository.globalEntrySum();
  }

  /**
   * Count over {@code accounts}, not {@code ledger_entries} — an account with no entries counts.
   */
  public int countAccounts() {
    return dsl.fetchCount(ACCOUNTS);
  }

  public List<AccountDriftRow> findDriftedAccounts() {
    return ledgerEntryRepository.findDriftedAccounts();
  }

  public ReconciliationReportsRecord insertReport(
      long globalSum,
      boolean driftDetected,
      JSONB driftDetails,
      int accountsChecked,
      int accountsDrifted) {
    return dsl.insertInto(RECONCILIATION_REPORTS)
        .set(RECONCILIATION_REPORTS.GLOBAL_SUM, globalSum)
        .set(RECONCILIATION_REPORTS.DRIFT_DETECTED, driftDetected)
        .set(RECONCILIATION_REPORTS.DRIFT_DETAILS, driftDetails)
        .set(RECONCILIATION_REPORTS.ACCOUNTS_CHECKED, accountsChecked)
        .set(RECONCILIATION_REPORTS.ACCOUNTS_DRIFTED, accountsDrifted)
        .returning()
        .fetchOne();
  }

  public Optional<ReconciliationReportsRecord> findLatest() {
    return Optional.ofNullable(
        dsl.selectFrom(RECONCILIATION_REPORTS)
            .orderBy(RECONCILIATION_REPORTS.RUN_AT.desc())
            .limit(1)
            .fetchOne());
  }
}
