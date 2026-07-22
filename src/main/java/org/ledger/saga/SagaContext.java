package org.ledger.saga;

import java.util.UUID;

/** The information a {@link SagaStep} needs to act -- nothing DSLContext-shaped (see ADR 0008). */
public record SagaContext(UUID sagaId, String currency) {}
