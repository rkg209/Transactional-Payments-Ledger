package org.ledger.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.ledger.reconciliation.ReconciliationReport;

public record ReconciliationReportResponse(
    UUID id,
    OffsetDateTime runAt,
    long globalSum,
    boolean driftDetected,
    int accountsChecked,
    int accountsDrifted,
    List<AccountDriftDetail> driftDetails) {

  public static ReconciliationReportResponse from(ReconciliationReport report) {
    return new ReconciliationReportResponse(
        report.id(),
        report.runAt(),
        report.globalSum(),
        report.driftDetected(),
        report.accountsChecked(),
        report.accountsDrifted(),
        report.details().stream().map(AccountDriftDetail::from).toList());
  }
}
