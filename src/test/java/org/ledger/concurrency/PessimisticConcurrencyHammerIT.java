package org.ledger.concurrency;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "ledger.concurrency-strategy=pessimistic")
class PessimisticConcurrencyHammerIT extends AbstractConcurrencyHammerIT {}
