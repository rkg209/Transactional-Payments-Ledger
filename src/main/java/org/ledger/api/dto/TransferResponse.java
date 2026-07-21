package org.ledger.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.ledger.transfer.TransferResult;

public record TransferResponse(
    UUID id,
    UUID fromAccountId,
    UUID toAccountId,
    long amount,
    String currency,
    String status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

  public static TransferResponse from(TransferResult result) {
    return new TransferResponse(
        result.transferId(),
        result.fromAccountId(),
        result.toAccountId(),
        result.amountMinor(),
        result.currency(),
        result.status().name(),
        result.createdAt(),
        result.updatedAt());
  }
}
