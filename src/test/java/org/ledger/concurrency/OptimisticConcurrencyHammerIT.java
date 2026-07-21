package org.ledger.concurrency;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "ledger.concurrency-strategy=optimistic",
      // 32 threads hammering one hot account is exactly the regime where optimistic locking
      // pays its retry cost: most attempts lose the CAS race at least once. The production
      // default (5) assumes lower contention; SPEC 0008 is where that default gets tuned against
      // a real benchmark, not this correctness test.
      "ledger.concurrency.max-attempts=200",
      "ledger.concurrency.backoff-base-ms=5"
    })
class OptimisticConcurrencyHammerIT extends AbstractConcurrencyHammerIT {}
