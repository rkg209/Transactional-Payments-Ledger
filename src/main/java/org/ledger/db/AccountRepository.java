package org.ledger.db;

import static org.ledger.db.generated.tables.Accounts.ACCOUNTS;

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
