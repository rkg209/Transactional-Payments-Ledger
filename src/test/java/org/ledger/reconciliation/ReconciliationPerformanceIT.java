package org.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The per-account drift query's grouped subquery over {@code ledger_entries} references only {@code
 * account_id}, {@code direction}, {@code amount_minor} -- every column in {@code
 * idx_ledger_entries_reconciliation} -- so it is capable of executing as an index-only scan. {@code
 * VACUUM} first, not just {@code ANALYZE}: index-only scans need the visibility map's all-visible
 * bits set to skip the heap, and only {@code VACUUM} sets those -- {@code ANALYZE} alone only
 * refreshes planner statistics, so skipping {@code VACUUM} silently falls back to a plain Index
 * Scan on a freshly-written table, forever, regardless of any other setting.
 *
 * <p>{@code SET LOCAL enable_seqscan = off} for the {@code EXPLAIN} itself: at this fixture's scale
 * (a few thousand rows) a real cost-based planner correctly prefers a sequential scan over the
 * index -- that is Postgres doing its job, not a regression. Forcing sequential scans off is how
 * you ask "can this query use the index at all," which is the actual, size-independent claim this
 * test makes; proving Postgres also picks it unprompted at production scale is the
 * concurrency-test's job, not this one's.
 */
class ReconciliationPerformanceIT extends AbstractPostgresIT {

  private static final int ACCOUNTS = 50;
  private static final int TRANSFERS_PER_ACCOUNT = 40;

  @Autowired private AccountService accountService;

  @Test
  void perAccountDriftQueryCanUseAnIndexOnlyScanNotOnlyASeqScan() {
    UUID genesis = createGenesisAccount("USD");
    for (int i = 0; i < ACCOUNTS; i++) {
      AccountResult account =
          accountService.createAccount("perf-" + i + "-" + UUID.randomUUID(), "USD", 0);
      for (int j = 0; j < TRANSFERS_PER_ACCOUNT; j++) {
        fundFromGenesis(genesis, account.id(), 100L, "USD");
      }
    }

    vacuumAnalyze("ledger_entries");
    vacuumAnalyze("accounts");

    String plan =
        tx.execute(
            status -> {
              // Disabling every heap-touching scan strategy leaves Index Only Scan the sole path
              // the planner can still take. Otherwise, at this fixture's small scale, the planner
              // reasonably prefers a plain Index Scan on the narrower idx_ledger_entries_account_id
              // (sorted output for the merge join, cheaper to scan) over the wider covering index
              // --
              // a real, cost-justified choice, just not the one this test needs to force to prove
              // the covering index is index-only-scannable at all.
              dsl.execute("SET LOCAL enable_seqscan = off");
              dsl.execute("SET LOCAL enable_indexscan = off");
              dsl.execute("SET LOCAL enable_bitmapscan = off");
              return String.join(
                  "\n",
                  dsl.fetch(
                          "EXPLAIN (ANALYZE, BUFFERS) "
                              + "SELECT a.id, a.balance, COALESCE(s.entry_sum, 0) "
                              + "FROM accounts a "
                              + "LEFT JOIN (SELECT account_id, "
                              + "                  SUM(CASE WHEN direction = 'CREDIT' THEN"
                              + " amount_minor ELSE -amount_minor END) AS entry_sum "
                              + "           FROM ledger_entries GROUP BY account_id) s "
                              + "  ON s.account_id = a.id "
                              + "WHERE a.balance <> COALESCE(s.entry_sum, 0)")
                      .map(r -> r.get(0, String.class)));
            });

    assertThat(plan).contains("Index Only Scan").contains("idx_ledger_entries_reconciliation");
  }

  /**
   * {@code VACUUM} cannot run inside a transaction block, and {@code TransactionTemplate} always
   * opens one -- so this bypasses it, toggling autocommit directly on a connection taken outside
   * any Spring-managed transaction (this datasource's pool otherwise defaults every connection to
   * {@code auto-commit=false}).
   */
  private void vacuumAnalyze(String table) {
    dsl.connection(
        connection -> {
          boolean previousAutoCommit = connection.getAutoCommit();
          connection.setAutoCommit(true);
          try (var statement = connection.createStatement()) {
            statement.execute("VACUUM ANALYZE " + table);
          } finally {
            connection.setAutoCommit(previousAutoCommit);
          }
        });
  }
}
