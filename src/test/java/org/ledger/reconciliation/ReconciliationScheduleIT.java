package org.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.test.context.TestPropertySource;

/**
 * The one IT that turns scheduling back on (every other reconciliation IT drives {@code runCheck()}
 * explicitly, since a background job firing mid-test against a TRUNCATE-ing suite is a flake
 * generator) -- proving the {@code @Scheduled} wiring itself, which nothing else exercises.
 */
@TestPropertySource(
    properties = {"ledger.reconciliation.scheduled=true", "ledger.reconciliation.interval-ms=200"})
class ReconciliationScheduleIT extends AbstractPostgresIT {

  @Test
  void scheduledJobPersistsAtLeastTwoReportsOnItsOwn() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      if (dsl.fetchCount(DSL.table("reconciliation_reports")) >= 2) {
        return;
      }
      Thread.sleep(50L);
    }
    assertThat(dsl.fetchCount(DSL.table("reconciliation_reports"))).isGreaterThanOrEqualTo(2);
  }
}
