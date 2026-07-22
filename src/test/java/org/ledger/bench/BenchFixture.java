package org.ledger.bench;

import java.util.UUID;
import org.jooq.DSLContext;
import org.ledger.account.AccountResult;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds one hot-account pair per benchmark iteration: a funded {@code source} and an empty {@code
 * hot} destination, exactly {@code AbstractConcurrencyHammerIT}'s shape. Funding goes through a
 * real transfer from a genesis account (the {@code AbstractPostgresIT.fundFromGenesis} pattern), so
 * {@code balance == Σentries} holds for every account this benchmark writes to.
 *
 * <p>Cannot extend {@code AbstractPostgresIT} (JUnit-bound; this runs under JMH), so its container
 * recipe ({@link BenchPostgres}) and funding discipline are copied rather than shared.
 */
final class BenchFixture {

  static final String CURRENCY = "USD";
  private static final long GENESIS_STARTING_CAPITAL = 1_000_000_000_000L;

  final UUID sourceId;
  final UUID hotId;

  private BenchFixture(UUID sourceId, UUID hotId) {
    this.sourceId = sourceId;
    this.hotId = hotId;
  }

  static BenchFixture seed(BenchContext ctx, long sourceStartBalance) {
    resetDatabase(ctx.dsl, ctx.tx);

    UUID genesisId =
        ctx.tx.execute(
            status ->
                ctx.accountService
                    .createAccount("bench-genesis-" + UUID.randomUUID(), CURRENCY, 0)
                    .id());
    seedBalance(ctx.dsl, ctx.tx, genesisId, GENESIS_STARTING_CAPITAL);

    AccountResult source =
        ctx.accountService.createAccount("bench-source-" + UUID.randomUUID(), CURRENCY, 0);
    AccountResult hot =
        ctx.accountService.createAccount("bench-hot-" + UUID.randomUUID(), CURRENCY, 0);

    ctx.transferService.execute(
        genesisId, source.id(), sourceStartBalance, CURRENCY, UUID.randomUUID().toString());

    return new BenchFixture(source.id(), hot.id());
  }

  private static void seedBalance(
      DSLContext dsl, TransactionTemplate tx, UUID accountId, long amount) {
    tx.executeWithoutResult(
        status -> dsl.execute("UPDATE accounts SET balance = ? WHERE id = ?", amount, accountId));
  }

  /** Same TRUNCATE + deadlock-retry as {@code AbstractPostgresIT.resetDatabase}. */
  private static void resetDatabase(DSLContext dsl, TransactionTemplate tx) {
    for (int attempt = 1; ; attempt++) {
      try {
        tx.executeWithoutResult(
            status ->
                dsl.execute(
                    "TRUNCATE accounts, transfers, ledger_entries, idempotency_keys, sagas,"
                        + " saga_steps, reconciliation_reports RESTART IDENTITY CASCADE"));
        return;
      } catch (DeadlockLoserDataAccessException e) {
        if (attempt >= 5) {
          throw e;
        }
      }
    }
  }
}
