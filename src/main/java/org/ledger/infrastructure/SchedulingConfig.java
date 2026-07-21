package org.ledger.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} (SPEC 0005's reconciliation job). Guarded off by default in tests via
 * {@code ledger.reconciliation.scheduled=false}: a background job firing mid-test against a
 * TRUNCATE-ing suite is a flake generator, and every reconciliation IT drives {@code runCheck()}
 * explicitly anyway. {@code ReconciliationScheduleIT} turns it back on to prove the wiring itself.
 */
@Configuration
@ConditionalOnProperty(
    name = "ledger.reconciliation.scheduled",
    havingValue = "true",
    matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {}
