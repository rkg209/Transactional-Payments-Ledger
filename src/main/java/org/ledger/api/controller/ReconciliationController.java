package org.ledger.api.controller;

import org.ledger.api.dto.ReconciliationReportResponse;
import org.ledger.reconciliation.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

  private final ReconciliationService reconciliationService;

  public ReconciliationController(ReconciliationService reconciliationService) {
    this.reconciliationService = reconciliationService;
  }

  /** Always 200: with no persisted report yet, this runs a fresh check rather than 404ing. */
  @GetMapping("/report")
  public ReconciliationReportResponse getReport() {
    return ReconciliationReportResponse.from(reconciliationService.latestReportOrRun());
  }

  /**
   * {@code idempotencyKey} is unused beyond triggering {@code MissingRequestHeaderException} on a
   * missing header — replay itself is handled entirely by {@code IdempotencyFilter} before this
   * method runs.
   */
  @PostMapping("/run")
  public ReconciliationReportResponse run(@RequestHeader("Idempotency-Key") String idempotencyKey) {
    return ReconciliationReportResponse.from(reconciliationService.runCheck());
  }
}
