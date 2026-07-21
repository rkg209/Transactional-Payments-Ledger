package org.ledger.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferResult(
    UUID transferId,
    UUID fromAccountId,
    UUID toAccountId,
    long amountMinor,
    String currency,
    TransferStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
