package org.ledger.concurrency;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "ledger.concurrency-strategy=optimistic",
      // 32 threads racing two hot accounts bidirectionally needs more headroom than the
      // production default (5); see OptimisticConcurrencyHammerIT for the same reasoning.
      "ledger.concurrency.max-attempts=200",
      "ledger.concurrency.backoff-base-ms=5"
    })
class OptimisticDeadlockIT extends AbstractDeadlockIT {}
