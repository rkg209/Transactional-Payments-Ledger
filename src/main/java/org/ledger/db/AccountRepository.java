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
   * Unconditional balance update. Callable only while the row is locked by {@code
   * PessimisticStrategy}'s {@code SELECT ... FOR UPDATE} — the held row lock is what makes this
   * safe, not anything in this method.
   */
  public void applyDelta(UUID accountId, long delta) {
    dsl.update(ACCOUNTS)
        .set(ACCOUNTS.BALANCE, ACCOUNTS.BALANCE.add(delta))
        .set(ACCOUNTS.VERSION, ACCOUNTS.VERSION.add(1))
        .set(ACCOUNTS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(ACCOUNTS.ID.eq(accountId))
        .execute();
  }

  /**
   * Plain read, both rows in one statement, ascending id order. Used by the optimistic strategy: no
   * lock is taken here, {@link #applyDeltaIfVersion} is where the race is actually decided.
   */
  public List<AccountsRecord> findOrdered(UUID a, UUID b) {
    return dsl.selectFrom(ACCOUNTS).where(ACCOUNTS.ID.in(a, b)).orderBy(ACCOUNTS.ID.asc()).fetch();
  }

  /**
   * Same read, {@code FOR UPDATE}. A single statement locking both rows in ascending id order — not
   * two sequential selects — is what makes the lock-ordering discipline structural rather than a
   * convention the caller could get wrong (ADR 0001).
   */
  public List<AccountsRecord> findOrderedForUpdate(UUID a, UUID b) {
    return dsl.selectFrom(ACCOUNTS)
        .where(ACCOUNTS.ID.in(a, b))
        .orderBy(ACCOUNTS.ID.asc())
        .forUpdate()
        .fetch();
  }

  /**
   * Version compare-and-set: {@code UPDATE ... WHERE id = ? AND version = ?}. Returns the number of
   * rows updated — 0 means a concurrent writer committed first and the caller must retry.
   */
  public int applyDeltaIfVersion(UUID accountId, long delta, long expectedVersion) {
    return dsl.update(ACCOUNTS)
        .set(ACCOUNTS.BALANCE, ACCOUNTS.BALANCE.add(delta))
        .set(ACCOUNTS.VERSION, ACCOUNTS.VERSION.add(1))
        .set(ACCOUNTS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(ACCOUNTS.ID.eq(accountId))
        .and(ACCOUNTS.VERSION.eq(expectedVersion))
        .execute();
  }
}
