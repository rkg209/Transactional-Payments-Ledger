package org.ledger.db;

import static org.ledger.db.generated.tables.Accounts.ACCOUNTS;
import static org.ledger.db.generated.tables.LedgerEntries.LEDGER_ENTRIES;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Only inserts and reads against {@code ledger_entries}. There is no update or delete method here
 * or anywhere else in this codebase: {@code ledger_entries} is append-only by construction, not
 * merely by convention. See {@code ArchitectureFitnessTest} (only this class may reference the
 * generated {@code LedgerEntries} types) and the {@code ledger_entries_immutable_tg} trigger for
 * the other two layers of the same guarantee.
 */
@Repository
public class LedgerEntryRepository {

  private final DSLContext dsl;

  public LedgerEntryRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Writes the DEBIT and CREDIT rows for one transfer from a single {@code amountMinor}, so an
   * unbalanced pair is structurally impossible: no code path in this repository can write one leg
   * without the other.
   */
  public void insertBalancedPair(
      UUID transferId, UUID debitAccountId, UUID creditAccountId, long amountMinor) {
    if (amountMinor <= 0) {
      throw new IllegalArgumentException("amountMinor must be positive, got " + amountMinor);
    }
    dsl.batch(
            dsl.insertInto(LEDGER_ENTRIES)
                .set(LEDGER_ENTRIES.TRANSFER_ID, transferId)
                .set(LEDGER_ENTRIES.ACCOUNT_ID, debitAccountId)
                .set(LEDGER_ENTRIES.DIRECTION, "DEBIT")
                .set(LEDGER_ENTRIES.AMOUNT_MINOR, amountMinor),
            dsl.insertInto(LEDGER_ENTRIES)
                .set(LEDGER_ENTRIES.TRANSFER_ID, transferId)
                .set(LEDGER_ENTRIES.ACCOUNT_ID, creditAccountId)
                .set(LEDGER_ENTRIES.DIRECTION, "CREDIT")
                .set(LEDGER_ENTRIES.AMOUNT_MINOR, amountMinor))
        .execute();
  }

  /** Signed sum of this account's entries: CREDIT is +amount, DEBIT is -amount. */
  public long entrySumForAccount(UUID accountId) {
    Long sum =
        dsl.select(DSL.coalesce(DSL.sum(signedAmount()), BigDecimal.ZERO))
            .from(LEDGER_ENTRIES)
            .where(LEDGER_ENTRIES.ACCOUNT_ID.eq(accountId))
            .fetchOne(0, Long.class);
    return sum;
  }

  /** Signed sum across every entry ever posted. Must always be zero. */
  public long globalEntrySum() {
    Long sum =
        dsl.select(DSL.coalesce(DSL.sum(signedAmount()), BigDecimal.ZERO))
            .from(LEDGER_ENTRIES)
            .fetchOne(0, Long.class);
    return sum;
  }

  /**
   * SPEC 0005 — every account whose {@code balance} disagrees with its signed entry sum, both read
   * from one statement (hence one snapshot): a transfer committing mid-check cannot manufacture
   * phantom drift by having the balance side see it and the entry-sum side not (or vice versa).
   * Lives here, not in {@code ReconciliationRepository}, because only this class may reference the
   * generated {@code LedgerEntries} types (see the class Javadoc and {@code
   * ArchitectureFitnessTest.only_ledger_entry_repository_touches_ledger_entries_generated_types}).
   *
   * <p>The {@code LEFT JOIN} is deliberate (SPEC 0005 decision 1): an account holding a nonzero
   * balance with zero entries is drift too, with {@code entrySum = 0} — an {@code INNER JOIN} would
   * make that case invisible. The grouped subquery references only {@code account_id}, {@code
   * direction}, {@code amount_minor} — every column in {@code idx_ledger_entries_reconciliation} —
   * so it is intended to execute as an index-only scan (verified by {@code
   * ReconciliationPerformanceIT}).
   */
  public List<AccountDriftRow> findDriftedAccounts() {
    Table<?> entrySums =
        dsl.select(LEDGER_ENTRIES.ACCOUNT_ID, DSL.sum(signedAmount()).as("entry_sum"))
            .from(LEDGER_ENTRIES)
            .groupBy(LEDGER_ENTRIES.ACCOUNT_ID)
            .asTable("s");

    Field<UUID> entryAccountId = entrySums.field("account_id", UUID.class);
    Field<BigDecimal> entrySum =
        DSL.coalesce(entrySums.field("entry_sum", BigDecimal.class), BigDecimal.ZERO);

    return dsl.select(ACCOUNTS.ID, ACCOUNTS.BALANCE, entrySum)
        .from(ACCOUNTS)
        .leftJoin(entrySums)
        .on(entryAccountId.eq(ACCOUNTS.ID))
        .where(ACCOUNTS.BALANCE.cast(BigDecimal.class).ne(entrySum))
        .fetch(
            r ->
                new AccountDriftRow(
                    r.get(ACCOUNTS.ID), r.get(ACCOUNTS.BALANCE), r.get(entrySum).longValue()));
  }

  private static Field<BigDecimal> signedAmount() {
    return DSL.when(LEDGER_ENTRIES.DIRECTION.eq("CREDIT"), LEDGER_ENTRIES.AMOUNT_MINOR)
        .otherwise(LEDGER_ENTRIES.AMOUNT_MINOR.neg())
        .cast(BigDecimal.class);
  }
}
