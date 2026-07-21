package org.ledger.db;

import static org.ledger.db.generated.tables.Accounts.ACCOUNTS;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.ledger.db.generated.tables.records.AccountsRecord;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

  private final DSLContext dsl;

  public AccountRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public AccountsRecord insert(String name, String currency, long minBalance) {
    return dsl.insertInto(ACCOUNTS)
        .set(ACCOUNTS.NAME, name)
        .set(ACCOUNTS.CURRENCY, currency)
        .set(ACCOUNTS.MIN_BALANCE, minBalance)
        .returning()
        .fetchOne();
  }

  public Optional<AccountsRecord> findById(UUID id) {
    return Optional.ofNullable(dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.eq(id)).fetchOne());
  }

  /**
   * Keyset page over {@code created_at ASC, id ASC}, fetching {@code limit + 1} rows so the caller
   * can determine {@code hasMore} without a separate {@code COUNT(*)}. Row-value comparison (not
   * {@code created_at > ? OR (created_at = ? AND id > ?)}) is what lets PostgreSQL use {@code
   * idx_accounts_created_at_id} as a single range scan.
   */
  public List<AccountsRecord> findPage(OffsetDateTime afterCreatedAt, UUID afterId, int limit) {
    org.jooq.Condition cursorCondition =
        afterCreatedAt == null || afterId == null
            ? DSL.noCondition()
            : DSL.row(ACCOUNTS.CREATED_AT, ACCOUNTS.ID).gt(DSL.row(afterCreatedAt, afterId));
    return dsl.selectFrom(ACCOUNTS)
        .where(cursorCondition)
        .orderBy(ACCOUNTS.CREATED_AT.asc(), ACCOUNTS.ID.asc())
        .limit(limit + 1)
        .fetch();
  }

  /**
   * Plain read-then-write balance update. Knowingly racy: no {@code FOR UPDATE}, no version
   * compare-and-set. SPEC 0004 replaces this with an explicit {@code ConcurrencyStrategy}; do not
   * pre-build locking here.
   */
  public void applyDelta(UUID accountId, long delta) {
    dsl.update(ACCOUNTS)
        .set(ACCOUNTS.BALANCE, ACCOUNTS.BALANCE.add(delta))
        .set(ACCOUNTS.VERSION, ACCOUNTS.VERSION.add(1))
        .set(ACCOUNTS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(ACCOUNTS.ID.eq(accountId))
        .execute();
  }
}
