package org.ledger.support;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

  @Autowired protected DSLContext dsl;
  @Autowired protected TransactionTemplate tx;

  /**
   * TRUNCATE, not DELETE: DELETE would fire (and be rejected by) the ledger_entries_immutable_tg
   * row-level trigger, since it guards UPDATE/DELETE, not TRUNCATE. TRUNCATE is the correct reset
   * for an append-only table between tests.
   */
  @BeforeEach
  void resetDatabase() {
    tx.executeWithoutResult(
        status ->
            dsl.execute(
                "TRUNCATE accounts, transfers, ledger_entries, idempotency_keys, sagas,"
                    + " saga_steps, reconciliation_reports RESTART IDENTITY CASCADE"));
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
}
