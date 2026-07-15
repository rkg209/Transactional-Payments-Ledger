package org.ledger.transfer;

/** Mirrors the {@code transfers.status} CHECK constraint in {@code V1__initial_schema.sql}. */
public enum TransferStatus {
  PENDING,
  COMPLETED,
  FAILED
}
