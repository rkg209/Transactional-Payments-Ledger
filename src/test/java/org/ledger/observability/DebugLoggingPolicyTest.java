package org.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractPostgresIT;
import org.slf4j.LoggerFactory;

/**
 * SPEC 0009 (NFR-23): under the base config (no {@code dev} profile active -- what docker-compose
 * and production actually ship), no logger is configured below INFO, in particular {@code
 * org.jooq.tools.LoggerListener}, whose bind-variable logging would put amounts and account UUIDs
 * in the logs at DEBUG. The DEBUG override for that logger lives in {@code application.yml}'s
 * `spring.config.activate.on-profile: dev` document, so simply not activating `dev` (the default
 * for every other IT in this suite) is the thing under test -- this class exists to make that a
 * checked invariant instead of an implicit assumption that could silently regress.
 */
class DebugLoggingPolicyTest extends AbstractPostgresIT {

  @Test
  void noLoggerIsConfiguredBelowInfoUnderTheBaseProfile() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    List<ch.qos.logback.classic.Logger> configured = context.getLoggerList();
    assertThat(configured).isNotEmpty();

    for (ch.qos.logback.classic.Logger logger : configured) {
      Level level = logger.getLevel();
      if (level == null) {
        continue;
      }
      assertThat(level.isGreaterOrEqual(Level.INFO))
          .as(
              "logger '%s' is explicitly configured at %s, below INFO, under the base"
                  + " (non-dev) profile",
              logger.getName(), level)
          .isTrue();
    }

    ch.qos.logback.classic.Logger jooqLogger = context.getLogger("org.jooq.tools.LoggerListener");
    assertThat(jooqLogger.getLevel()).as("must inherit, not be set explicitly, here").isNull();
    assertThat(jooqLogger.getEffectiveLevel().isGreaterOrEqual(Level.INFO)).isTrue();
  }
}
