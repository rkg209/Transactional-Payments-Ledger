package org.ledger.transfer;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

  private final UUID transferId;

  public TransferNotFoundException(UUID transferId) {
    super("Transfer not found: " + transferId);
    this.transferId = transferId;
  }

  public UUID transferId() {
    return transferId;
  }
}
