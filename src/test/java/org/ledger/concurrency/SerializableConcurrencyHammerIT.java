package org.ledger.concurrency;

import org.springframework.test.context.TestPropertySource;

/**
 * Proves the retry loop absorbs Postgres's {@code 40001} serialization failures under {@code
 * SERIALIZABLE} rather than surfacing them as 500s (SPEC 0004, ADR 0006).
 */
@TestPropertySource(
    properties = {
      "ledger.concurrency-strategy=optimistic",
      "ledger.isolation-level=serializable",
      // 32 threads x 25 transfers into one hot account under SERIALIZABLE produces far more
      // 40001 serialization failures than the production default (5) absorbs; the point of this
      // test is that the loop absorbs them, not that 5 is the right bound for this isolation
      // level, so it is raised here rather than tuning the loop's default for a corner case.
      "ledger.concurrency.max-attempts=200",
      "ledger.concurrency.backoff-base-ms=5"
    })
class SerializableConcurrencyHammerIT extends AbstractConcurrencyHammerIT {}
