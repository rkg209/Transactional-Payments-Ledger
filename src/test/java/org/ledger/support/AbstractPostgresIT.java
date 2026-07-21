package org.ledger.support;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.ledger.db.AccountRepository;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests against a real PostgreSQL (CON-9, NFR-17: in-memory databases
 * are prohibited for anything touching transactional correctness).
 *
 * <p>Uses the Testcontainers singleton-container pattern: the container is started once, in a
 * static initializer, and never explicitly stopped -- Ryuk reaps it when the JVM exits. Every
 * subclass in the same test run shares one running Postgres instead of paying container-startup
 * cost per test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "ledger.reconciliation.scheduled=false")
public abstract class AbstractPostgresIT {

  /**
   * {@code max_connections} raised well past Postgres's default 100: each distinct
   * {@code @TestPropertySource} combination (SPEC 0004's per-strategy concurrency ITs, among
   * others) gets its own Spring context and its own up-to-20-connection Hikari pool, and several
   * stay open simultaneously under the Spring test context cache. The default limit was hit in
   * practice, as "FATAL: sorry, too many clients already" rather than a slow, mysterious flake.
   */
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("ledger")
          .withUsername("ledger")
          .withPassword("ledger")
          .withCommand("postgres", "-c", "max_connections=300");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  /**
   * The genesis account's seeded starting capital (SPEC 0005). {@code
   * accounts_min_balance_gte_zero} (`min_balance >= 0`, unconditionally, for every account) makes a
   * permanently-negative-balance "genesis" account structurally impossible in this schema — no
   * account can ever go below 0, which means no account can manufacture capital for another without
   * first holding it. Genesis therefore needs the same one-time, entry-less seed as {@link
   * #seedInitialBalance}; it funds downstream accounts through real transfers from that seed, well
   * clear of any test's total funding needs.
   */
  protected static final long GENESIS_STARTING_CAPITAL = 1_000_000_000_000L;

  @Autowired protected DSLContext dsl;
  @Autowired protected TransactionTemplate tx;
  @Autowired protected AccountRepository accountRepository;
  @Autowired protected TransferService transferService;

  /**
   * TRUNCATE, not DELETE: DELETE would fire (and be rejected by) the ledger_entries_immutable_tg
   * row-level trigger, since it guards UPDATE/DELETE, not TRUNCATE. TRUNCATE is the correct reset
   * for an append-only table between tests.
   *
   * <p>Retries on deadlock: {@code ReconciliationScheduleIT} runs with the real {@code @Scheduled}
   * job live, so a scheduled run's read/insert can legitimately be in flight, holding locks in the
   * opposite order from this {@code TRUNCATE}, when a test method starts. That is a real,
   * expected-to-happen-sometimes deadlock between two genuine transactions, not a bug in either one
   * — the fix is to retry the loser, exactly as {@code TransferService} already does for the same
   * SQLSTATE on the production write path.
   */
  @BeforeEach
  void resetDatabase() {
    for (int attempt = 1; ; attempt++) {
      try {
        tx.executeWithoutResult(
            status ->
                dsl.execute(
                    "TRUNCATE accounts, transfers, ledger_entries, idempotency_keys, sagas,"
                        + " saga_steps, reconciliation_reports RESTART IDENTITY CASCADE"));
        return;
      } catch (org.springframework.dao.DeadlockLoserDataAccessException e) {
        if (attempt >= 5) {
          throw e;
        }
      }
    }
  }

  /**
   * Seeds an account's balance directly via raw SQL, bypassing {@code AccountService} and the
   * ledger entirely. This does <b>not</b> go through {@code TransferService}, so it produces no
   * matching {@code ledger_entries} -- it represents capital that already existed before this
   * system's ledger began (the same way a real ledger's chart of accounts is seeded from a legacy
   * balance snapshot), not money created by this system's own operations.
   *
   * <p>Deliberately excluded from the "balance == sum(entries)" assertion for that reason. Every
   * account balance produced <i>through</i> {@code TransferService} still satisfies balance == seed
   * + sum(entries) exactly, which is what SPEC 0001 actually claims.
   */
  protected void seedInitialBalance(UUID accountId, long amount) {
    tx.executeWithoutResult(
        status -> dsl.execute("UPDATE accounts SET balance = ? WHERE id = ?", amount, accountId));
  }

  /**
   * Like {@link #seedInitialBalance}, but also raises {@code min_balance} after the account already
   * exists. {@code AccountService.createAccount} cannot do this directly: the accounts table
   * defaults a new row's {@code balance} to 0, so creating an account with {@code minBalance > 0}
   * would violate {@code accounts_balance_gte_min} at INSERT time, before any balance can be
   * seeded. Setting both columns in the same UPDATE satisfies the CHECK in its final state.
   */
  protected void seedAccountState(UUID accountId, long balance, long minBalance) {
    tx.executeWithoutResult(
        status ->
            dsl.execute(
                "UPDATE accounts SET balance = ?, min_balance = ? WHERE id = ?",
                balance,
                minBalance,
                accountId));
  }

  /**
   * A system/genesis account for reconciliation fixtures to fund test accounts <i>through</i>
   * {@code TransferService}, so every downstream account satisfies {@code balance == Σentries}
   * exactly (unlike {@link #seedInitialBalance} directly on each test account). Genesis itself is
   * seeded once with {@link #GENESIS_STARTING_CAPITAL} — external, entry-less capital, exactly like
   * {@link #seedInitialBalance} — so it will show up as the single, known, constant-magnitude drift
   * in any reconciliation check regardless of how much is drawn from it: {@code balance} and {@code
   * entrySum} move together for every unit actually transferred out, leaving the gap fixed at
   * exactly the seed. A truly zero-drift genesis is not achievable under this schema: {@code
   * accounts_min_balance_gte_zero} means no account can ever go negative to donate the first unit
   * of capital, so some account, somewhere, must start with an external seed.
   */
  protected UUID createGenesisAccount(String currency) {
    return createGenesisAccount(currency, GENESIS_STARTING_CAPITAL);
  }

  /**
   * As {@link #createGenesisAccount(String)}, with an explicit starting capital. Wrapped in {@code
   * tx.execute} explicitly: {@code AccountRepository.insert} carries no {@code @Transactional} of
   * its own (only {@code AccountService} does, and this helper deliberately bypasses it), and with
   * {@code spring.datasource.hikari.auto-commit=false} an unwrapped call never commits.
   */
  protected UUID createGenesisAccount(String currency, long startingCapital) {
    UUID genesisId =
        tx.execute(
            status ->
                accountRepository.insert("genesis-" + UUID.randomUUID(), currency, 0).getId());
    seedInitialBalance(genesisId, startingCapital);
    return genesisId;
  }

  /** Moves {@code amount} out of a genesis account into {@code accountId} via a real transfer. */
  protected void fundFromGenesis(
      UUID genesisAccountId, UUID accountId, long amount, String currency) {
    transferService.execute(
        genesisAccountId, accountId, amount, currency, UUID.randomUUID().toString());
  }

  /**
   * Corrupts {@code accounts.balance} directly, bypassing every service and lock, so the
   * reconciliation detector has something real to detect. Test-only, by construction: no production
   * code path can do this (SPEC 0005 decision 5 — the {@code reconciliation} package contains no
   * write path to {@code accounts} or {@code ledger_entries}).
   */
  protected void injectDrift(UUID accountId, long delta) {
    tx.executeWithoutResult(
        status ->
            dsl.execute(
                "UPDATE accounts SET balance = balance + ? WHERE id = ?", delta, accountId));
  }
}
