package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SPEC 0000 — walking skeleton.
 *
 * <p>Proves the pipeline is real end to end: Spring boots, Flyway migrates a blank PostgreSQL, jOOQ
 * talks to it, and {@code /health} answers. Every correctness claim in every later spec rests on
 * this being sound, so it is worth asserting rather than assuming.
 *
 * <p>Runs against a REAL PostgreSQL in Testcontainers. In-memory databases (H2, HSQLDB) are
 * prohibited (CON-9, NFR-17): they do not implement PostgreSQL's locking, isolation, or triggers,
 * so a green test against one would prove nothing about the behavior we actually depend on.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalkingSkeletonIT {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("ledger")
          .withUsername("ledger")
          .withPassword("ledger");

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private DSLContext dsl;
  @Autowired private TestRestTemplate rest;
  @Autowired private TransactionTemplate tx;

  @LocalServerPort private int port;

  /** The context loads and jOOQ is genuinely wired to the container, not to a mock. */
  @Test
  void contextLoadsAndDatabaseIsReachable() {
    Integer one = dsl.select(DSL.one()).fetchOne(0, Integer.class);
    assertThat(one).isEqualTo(1);
  }

  /** Flyway applied both migrations. Proves the schema is reproducible from scratch (NFR-13). */
  @Test
  void flywayAppliedAllMigrations() {
    List<String> versions =
        dsl.select(DSL.field("version", String.class))
            .from(DSL.table("flyway_schema_history"))
            .where(DSL.field("success", Boolean.class).isTrue())
            .fetch(0, String.class);

    assertThat(versions).contains("1", "2");
  }

  /** All seven tables from the authoritative schema exist. */
  @Test
  void schemaContainsAllSevenTables() {
    List<String> tables =
        dsl.select(DSL.field("table_name", String.class))
            .from(DSL.table("information_schema.tables"))
            .where(DSL.field("table_schema").eq("public"))
            .fetch(0, String.class);

    assertThat(tables)
        .contains(
            "accounts",
            "transfers",
            "ledger_entries",
            "idempotency_keys",
            "sagas",
            "saga_steps",
            "reconciliation_reports");
  }

  /** FR-10: /health reports service and database status. */
  @Test
  void healthEndpointReportsDatabaseUp() {
    ResponseEntity<String> response =
        rest.getForEntity("http://localhost:" + port + "/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"").contains("\"database\":\"UP\"");
  }

  /**
   * Invariant #2, enforced by the database itself.
   *
   * <p>This is the one real correctness assertion in the skeleton, and it earns its place here: the
   * append-only guarantee is the foundation the zero-sum invariant is checkable on at all. The
   * application promises never to mutate a posted entry — this proves the promise is backed by the
   * database, so it holds even against a stray psql session or a future bug.
   */
  @Test
  void ledgerEntriesRejectUpdateAndDelete() {
    UUID accountId = UUID.randomUUID();
    UUID otherAccountId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    UUID entryId = UUID.randomUUID();

    // The setup must run inside an explicit transaction so it COMMITS.
    // HikariCP is configured with auto-commit=false (see application.yml), so a
    // statement issued outside a transaction is never committed and is silently
    // discarded when the connection returns to the pool. That is the intended
    // behavior -- the service layer owns every transaction boundary -- but it
    // means tests must open one too.
    tx.executeWithoutResult(
        status -> {
          dsl.execute(
              "INSERT INTO accounts (id, name, currency, balance) VALUES (?, ?, 'USD', 0)",
              accountId,
              "immutability-test-src");
          dsl.execute(
              "INSERT INTO accounts (id, name, currency, balance) VALUES (?, ?, 'USD', 0)",
              otherAccountId,
              "immutability-test-dst");
          dsl.execute(
              "INSERT INTO transfers (id, from_account_id, to_account_id, amount_minor, currency,"
                  + " status) VALUES (?, ?, ?, 100, 'USD', 'COMPLETED')",
              transferId,
              accountId,
              otherAccountId);
          dsl.execute(
              "INSERT INTO ledger_entries (id, transfer_id, account_id, direction, amount_minor)"
                  + " VALUES (?, ?, ?, 'DEBIT', 100)",
              entryId,
              transferId,
              accountId);
        });

    // Each attempt gets its own transaction: the trigger's exception aborts the
    // transaction it fires in, so they cannot share one.
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        dsl.execute(
                            "UPDATE ledger_entries SET amount_minor = 999 WHERE id = ?", entryId)))
        .hasMessageContaining("append-only");

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> dsl.execute("DELETE FROM ledger_entries WHERE id = ?", entryId)))
        .hasMessageContaining("append-only");

    // The entry survived both attempts, unchanged.
    Long amount =
        tx.execute(
            status ->
                dsl.select(DSL.field("amount_minor", Long.class))
                    .from(DSL.table("ledger_entries"))
                    .where(DSL.field("id", UUID.class).eq(entryId))
                    .fetchOne(0, Long.class));

    assertThat(amount).isEqualTo(100L);
  }
}
