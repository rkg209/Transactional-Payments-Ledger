package org.ledger.db;

import static org.ledger.db.generated.tables.LedgerEntries.LEDGER_ENTRIES;

import java.math.BigDecimal;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
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

  private static Field<BigDecimal> signedAmount() {
    return DSL.when(LEDGER_ENTRIES.DIRECTION.eq("CREDIT"), LEDGER_ENTRIES.AMOUNT_MINOR)
        .otherwise(LEDGER_ENTRIES.AMOUNT_MINOR.neg())
        .cast(BigDecimal.class);
  }
}
