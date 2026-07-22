package org.ledger.infrastructure;

import org.ledger.concurrency.ConcurrencyStrategy;
import org.ledger.concurrency.OptimisticStrategy;
import org.ledger.concurrency.PessimisticStrategy;
import org.ledger.db.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The composition root for SPEC 0004: picks the {@link ConcurrencyStrategy} and the transaction
 * isolation level from config, both at startup, both fail-fast on an unrecognized value — a typo
 * must not silently fall back to racy behavior (ADR 0006).
 */
@Configuration
public class ConcurrencyConfig {

  private static final Logger log = LoggerFactory.getLogger(ConcurrencyConfig.class);

  @Bean
  public OptimisticStrategy optimisticStrategy(AccountRepository accountRepository) {
    return new OptimisticStrategy(accountRepository);
  }

  @Bean
  public PessimisticStrategy pessimisticStrategy(AccountRepository accountRepository) {
    return new PessimisticStrategy(accountRepository);
  }

  @Bean
  @Primary
  public ConcurrencyStrategy concurrencyStrategy(
      @Value("${ledger.concurrency-strategy}") String strategyName,
      OptimisticStrategy optimisticStrategy,
      PessimisticStrategy pessimisticStrategy) {
    ConcurrencyStrategy chosen =
        switch (strategyName.trim().toLowerCase()) {
          case "optimistic" -> optimisticStrategy;
          case "pessimistic" -> pessimisticStrategy;
          default ->
              throw new IllegalStateException(
                  "Unrecognized ledger.concurrency-strategy '"
                      + strategyName
                      + "'; valid values are 'optimistic', 'pessimistic'");
        };
    log.info("Active concurrency strategy: {}", chosen.name());
    return chosen;
  }

  @Bean
  public TransactionTemplate ledgerTransactionTemplate(
      PlatformTransactionManager transactionManager,
      @Value("${ledger.isolation-level}") String isolationLevel) {
    int isolation =
        switch (isolationLevel.trim().toLowerCase()) {
          case "read_committed" -> TransactionDefinition.ISOLATION_READ_COMMITTED;
          case "serializable" -> TransactionDefinition.ISOLATION_SERIALIZABLE;
          default ->
              throw new IllegalStateException(
                  "Unrecognized ledger.isolation-level '"
                      + isolationLevel
                      + "'; valid values are 'read_committed', 'serializable'");
        };
    log.info("Active transaction isolation level: {}", isolationLevel);
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setIsolationLevel(isolation);
    return template;
  }

  @Bean
  public LedgerConcurrencyProperties ledgerConcurrencyProperties(
      @Value("${ledger.concurrency.max-attempts:5}") int maxAttempts,
      @Value("${ledger.concurrency.backoff-base-ms:50}") long backoffBaseMs) {
    return new LedgerConcurrencyProperties(maxAttempts, backoffBaseMs);
  }

  /** SPEC 0010 — see {@link LedgerIdempotencyProperties} for why the default is 30 s. */
  @Bean
  public LedgerIdempotencyProperties ledgerIdempotencyProperties(
      @Value("${ledger.idempotency.stale-claim-after-ms:30000}") long staleClaimAfterMs) {
    return new LedgerIdempotencyProperties(java.time.Duration.ofMillis(staleClaimAfterMs));
  }
}
