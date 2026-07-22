package org.ledger.bench;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * SPEC 0008 -- one real PostgreSQL container shared by every matrix cell's {@link BenchContext},
 * exactly the {@code AbstractPostgresIT} singleton-container recipe (started once, in a static
 * initializer, never explicitly stopped -- Ryuk reaps it). {@code TransferBenchmark} cannot extend
 * {@code AbstractPostgresIT} (it is JUnit-bound; JMH state classes are not), so the container
 * recipe is copied here rather than shared.
 */
final class BenchPostgres {

  /**
   * Same headroom as {@code AbstractPostgresIT}: up to four cached Spring contexts, each with its
   * own Hikari pool sized to the highest contention level benchmarked.
   */
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("ledger")
          .withUsername("ledger")
          .withPassword("ledger")
          .withCommand("postgres", "-c", "max_connections=300");

  static {
    POSTGRES.start();
  }

  private BenchPostgres() {}
}
