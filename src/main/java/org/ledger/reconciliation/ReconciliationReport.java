package org.ledger.reconciliation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
    UUID id,
    OffsetDateTime runAt,
    long globalSum,
    boolean driftDetected,
    int accountsChecked,
    int accountsDrifted,
    List<AccountDrift> details) {}
