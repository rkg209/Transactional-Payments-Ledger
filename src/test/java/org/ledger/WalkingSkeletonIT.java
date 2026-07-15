package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 0000 — walking skeleton.
 *
 * <p>Proves the pipeline is real end to end: Spring boots, Flyway migrates a blank PostgreSQL, jOOQ
 * talks to it, and {@code /health} answers. Every correctness claim in every later spec rests on
 * this being sound, so it is worth asserting rather than assuming.
 *
 * <p>The ledger_entries immutability assertion moved to {@code LedgerImmutabilityIT} in SPEC 0001,
 * now that real services exist to seed data with instead of raw INSERT SQL.
 */
class WalkingSkeletonIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate rest;

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
}
