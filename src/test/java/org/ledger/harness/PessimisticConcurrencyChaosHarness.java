package org.ledger.harness;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "ledger.concurrency-strategy=pessimistic",
      "spring.datasource.hikari.maximum-pool-size=50"
    })
class PessimisticConcurrencyChaosHarness extends AbstractConcurrencyChaosHarness {

  @Override
  protected String strategyName() {
    return "pessimistic";
  }
}
