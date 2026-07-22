package org.ledger.bench;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.ledger.LedgerApplication;
import org.ledger.account.AccountService;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.transfer.TransferService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One Spring context for one (strategy, isolation) matrix cell. {@code ConcurrencyConfig} picks
 * both {@link org.ledger.concurrency.ConcurrencyStrategy} and the transaction isolation level from
 * config at startup with no runtime switch -- so unlike an ordinary IT, each cell of SPEC 0008's
 * 2x2 matrix genuinely needs its own boot. {@code TransferBenchmark} caches and reuses one instance
 * of this per (strategy, isolation) pair across every contention level swept for that pair.
 */
final class BenchContext implements AutoCloseable {

  private final ConfigurableApplicationContext context;
  final TransferService transferService;
  final AccountService accountService;
  final DSLContext dsl;
  final TransactionTemplate tx;
  final LedgerEntryRepository ledgerEntryRepository;

  /**
   * These go in via an {@code ApplicationContextInitializer} that {@code addFirst}s a {@link
   * MapPropertySource}, not {@code SpringApplicationBuilder.properties(...)}: that method sets
   * Boot's <i>default</i> properties, the lowest-precedence source there is, so it never wins
   * against {@code application.yml}'s already-concrete {@code spring.datasource.url} (which has a
   * hardcoded {@code localhost:5432} fallback) -- the context would boot against the dev/prod
   * Postgres address instead of {@link BenchPostgres}, or fail outright if nothing is listening
   * there. {@code addFirst} makes this source the highest-precedence one in the environment,
   * unconditionally.
   */
  static BenchContext boot(String strategy, String isolation, int poolSize) {
    Map<String, Object> overrides = new LinkedHashMap<>();
    overrides.put("spring.datasource.url", BenchPostgres.POSTGRES.getJdbcUrl());
    overrides.put("spring.datasource.username", BenchPostgres.POSTGRES.getUsername());
    overrides.put("spring.datasource.password", BenchPostgres.POSTGRES.getPassword());
    overrides.put("spring.datasource.hikari.maximum-pool-size", poolSize);
    overrides.put("ledger.concurrency-strategy", strategy);
    overrides.put("ledger.isolation-level", isolation);
    overrides.put("ledger.reconciliation.scheduled", "false");

    ConfigurableApplicationContext ctx =
        new SpringApplicationBuilder(LedgerApplication.class)
            .web(WebApplicationType.NONE)
            .initializers(
                applicationContext ->
                    applicationContext
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource("bench-context", overrides)))
            .run();
    // Boot's LoggingApplicationListener re-applies application.yml's logging.level.* (including
    // org.jooq.tools.LoggerListener: DEBUG) as part of *this* run() call, so BenchRunner's
    // one-time silenceLogging() at process start is undone the moment the first context boots.
    // Re-silence after every boot, not just once.
    silenceLogging();
    return new BenchContext(ctx);
  }

  /**
   * See {@code BenchRunner.silenceLogging} for why this exists at all: DEBUG-level jOOQ bind-value
   * logging under a concurrent burst would measure logging I/O, not lock contention.
   */
  private static void silenceLogging() {
    ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
    ((Logger) LoggerFactory.getLogger("org.jooq.tools.LoggerListener")).setLevel(Level.WARN);
  }

  private BenchContext(ConfigurableApplicationContext context) {
    this.context = context;
    this.transferService = context.getBean(TransferService.class);
    this.accountService = context.getBean(AccountService.class);
    this.dsl = context.getBean(DSLContext.class);
    this.tx = context.getBean(TransactionTemplate.class);
    this.ledgerEntryRepository = context.getBean(LedgerEntryRepository.class);
  }

  @Override
  public void close() {
    context.close();
  }
}
