package org.ledger.account;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResult(
    UUID id,
    String name,
    String currency,
    long minBalance,
    long balance,
    long version,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
