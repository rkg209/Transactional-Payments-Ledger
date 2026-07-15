package org.ledger.transfer;

import java.util.UUID;

public record TransferResult(UUID transferId, TransferStatus status) {}
