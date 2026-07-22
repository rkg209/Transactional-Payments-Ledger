package org.ledger.saga;

import java.util.UUID;

/**
 * One {@code (DEBIT, CREDIT)} pair from the request's flat step list, validated and paired up --
 * the chain-of-legs model's unit of work. One leg becomes one ordinary transfer (ADR 0008).
 */
public record SagaLeg(int stepIndex, UUID fromAccountId, UUID toAccountId, long amountMinor) {}
