package org.ledger.harness;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "ledger.concurrency-strategy=optimistic",
      // Twelve ordered pairs over four hot accounts under a 10k-request storm is real CAS
      // contention; a higher retry ceiling keeps that contention a re-send, not a hard failure.
      "ledger.concurrency.max-attempts=30",
      "ledger.concurrency.backoff-base-ms=10",
      "spring.datasource.hikari.maximum-pool-size=50"
    })
class OptimisticConcurrencyChaosHarness extends AbstractConcurrencyChaosHarness {

  @Override
  protected String strategyName() {
    return "optimistic";
  }
}
