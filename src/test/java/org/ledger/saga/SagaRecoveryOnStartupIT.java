package org.ledger.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.ledger.db.SagaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the Tomcat-pause / {@code SagaRecoveryRunner} wiring itself, not the recovery algorithm
 * ({@code SagaCrashRecoveryIT} already covers that thoroughly). A saga interrupted mid-step is
 * seeded directly into the database <b>before</b> the Spring context is ever created; by the time
 * this test can observe it, the saga is terminal -- proof that recovery genuinely ran as part of
 * application startup.
 *
 * <p>Deliberately does not extend {@code AbstractPostgresIT}: that base's {@code @BeforeEach}
 * TRUNCATEs every table on every test, which would erase the seeded row before this test's
 * assertion could run. This class owns its own container and runs Flyway itself, once, before the
 * seed insert -- the app's own Flyway run against the same schema is then a no-op.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "ledger.reconciliation.scheduled=false")
class SagaRecoveryOnStartupIT {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("ledger")
          .withUsername("ledger")
          .withPassword("ledger");

  private static final UUID SAGA_ID = UUID.randomUUID();

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @BeforeAll
  static void seedInterruptedSagaBeforeSpringContextBoots() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    String payload =
        ("{\"type\":\"MULTI_LEG_TRANSFER\",\"currency\":\"USD\",\"description\":null,"
                + "\"legs\":[{\"stepIndex\":0,\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\","
                + "\"amountMinor\":100}]}")
            .formatted(UUID.randomUUID(), UUID.randomUUID());

    try (Connection conn =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement sagaStmt =
            conn.prepareStatement(
                "INSERT INTO sagas (id, type, state, current_step, payload) VALUES (?, ?,"
                    + " 'STARTED', 0, ?::jsonb)");
        PreparedStatement stepStmt =
            conn.prepareStatement(
                "INSERT INTO saga_steps (saga_id, step_index, step_type, state) VALUES (?, 0,"
                    + " 'LEG_TRANSFER', 'IN_PROGRESS')")) {
      sagaStmt.setObject(1, SAGA_ID);
      sagaStmt.setString(2, "MULTI_LEG_TRANSFER");
      sagaStmt.setString(3, payload);
      sagaStmt.executeUpdate();

      stepStmt.setObject(1, SAGA_ID);
      stepStmt.executeUpdate();
    }
  }

  @Autowired private SagaRepository sagaRepository;

  @Test
  void interruptedSagaIsReconciledDuringApplicationStartup() {
    // The leg's forward key was never posted as a real transfer, so recovery has nothing to
    // complete -- it goes straight to marking the saga COMPENSATED. That the state moved off
    // STARTED at all, with no test code ever calling recover() directly, is the proof this test
    // exists for.
    var saga = sagaRepository.findById(SAGA_ID).orElseThrow();
    assertThat(saga.getState()).isEqualTo("COMPENSATED");
  }
}
