package org.ledger.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jooq.JSONB;
import org.ledger.db.AccountDriftRow;
import org.ledger.db.ReconciliationRepository;
import org.ledger.db.generated.tables.records.ReconciliationReportsRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-derives both invariants from the ledger and persists the verdict. Plain {@link
 * TransactionTemplate}s, not {@code @Transactional}, for the same reason {@code TransferService}
 * uses them: {@link #runCheck()} calls its own read step and its own write step, and a
 * self-invocation through {@code this} never goes through the Spring proxy that
 * {@code @Transactional} relies on — the two steps genuinely need different propagation (the read
 * is one statement in a read-only transaction; the report must survive even if that read
 * transaction were rolled back, hence {@code REQUIRES_NEW}), so the transaction boundary has to be
 * explicit here.
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
  private static final int MAX_LOGGED_DRIFTED_ACCOUNTS = 20;
  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private final ReconciliationRepository repository;
  private final TransactionTemplate readOnlyTransaction;
  private final TransactionTemplate reportTransaction;
  private final AtomicLong globalSumGauge = new AtomicLong();
  private final AtomicLong statusGauge = new AtomicLong(1);
  private final Counter driftCounter;
  private final Counter runsCounter;

  public ReconciliationService(
      ReconciliationRepository repository,
      PlatformTransactionManager transactionManager,
      MeterRegistry meterRegistry) {
    this.repository = repository;

    this.readOnlyTransaction = new TransactionTemplate(transactionManager);
    this.readOnlyTransaction.setReadOnly(true);

    this.reportTransaction = new TransactionTemplate(transactionManager);
    this.reportTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    // registry.gauge(...) called once, here, against a held AtomicLong: calling it per run would
    // register a new meter every run and leak them.
    meterRegistry.gauge("reconciliation.global_sum", globalSumGauge, AtomicLong::doubleValue);
    // 1 = pass, 0 = drift (NFR-20): scrapable directly, rather than inferred from a counter delta.
    meterRegistry.gauge("reconciliation.status", statusGauge, AtomicLong::doubleValue);
    this.driftCounter = meterRegistry.counter("reconciliation.drift.count");
    this.runsCounter = meterRegistry.counter("reconciliation.runs.total");
  }

  @Scheduled(fixedDelayString = "${ledger.reconciliation.interval-ms:60000}")
  public ReconciliationReport runCheck() {
    Snapshot snapshot = readOnlyTransaction.execute(status -> readSnapshot());
    ReconciliationReport report = reportTransaction.execute(status -> persist(snapshot));

    runsCounter.increment();
    globalSumGauge.set(snapshot.globalSum());
    statusGauge.set(report.driftDetected() ? 0 : 1);
    if (report.driftDetected()) {
      driftCounter.increment(snapshot.drifted().size() + (snapshot.globalSum() != 0 ? 1 : 0));
      log.error(
          "RECONCILIATION_DRIFT globalSum={} accountsChecked={} accountsDrifted={} firstDrifted={}",
          snapshot.globalSum(),
          snapshot.accountsChecked(),
          snapshot.drifted().size(),
          snapshot.drifted().stream()
              .limit(MAX_LOGGED_DRIFTED_ACCOUNTS)
              .map(AccountDrift::accountId)
              .toList());
    }
    log.info(
        "reconciliation_run runId={} globalSum={} driftedCount={} status={}",
        report.id(),
        snapshot.globalSum(),
        snapshot.drifted().size(),
        report.driftDetected() ? "DRIFT" : "PASS");
    return report;
  }

  /** Serves {@code GET /reconciliation/report}: the last persisted report, or a fresh check. */
  public ReconciliationReport latestReportOrRun() {
    return repository.findLatest().map(ReconciliationService::toReport).orElseGet(this::runCheck);
  }

  private Snapshot readSnapshot() {
    long globalSum = repository.globalEntrySum();
    List<AccountDriftRow> driftedRows = repository.findDriftedAccounts();
    int accountsChecked = repository.countAccounts();
    List<AccountDrift> drifted = driftedRows.stream().map(AccountDrift::from).toList();
    return new Snapshot(globalSum, accountsChecked, drifted);
  }

  private ReconciliationReport persist(Snapshot snapshot) {
    boolean driftDetected = snapshot.globalSum() != 0 || !snapshot.drifted().isEmpty();
    JSONB driftDetails = driftDetected ? toJsonb(snapshot.drifted()) : null;
    ReconciliationReportsRecord record =
        repository.insertReport(
            snapshot.globalSum(),
            driftDetected,
            driftDetails,
            snapshot.accountsChecked(),
            snapshot.drifted().size());
    return new ReconciliationReport(
        record.getId(),
        record.getRunAt(),
        snapshot.globalSum(),
        driftDetected,
        snapshot.accountsChecked(),
        snapshot.drifted().size(),
        snapshot.drifted());
  }

  private static JSONB toJsonb(List<AccountDrift> drifted) {
    try {
      return JSONB.jsonb(MAPPER.writeValueAsString(drifted));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize reconciliation drift details", e);
    }
  }

  private static ReconciliationReport toReport(ReconciliationReportsRecord record) {
    return new ReconciliationReport(
        record.getId(),
        record.getRunAt(),
        record.getGlobalSum(),
        record.getDriftDetected(),
        record.getAccountsChecked(),
        record.getAccountsDrifted(),
        parseDriftDetails(record.getDriftDetails()));
  }

  private static List<AccountDrift> parseDriftDetails(JSONB driftDetails) {
    if (driftDetails == null) {
      return List.of();
    }
    try {
      return List.of(MAPPER.readValue(driftDetails.data(), AccountDrift[].class));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse persisted reconciliation drift details", e);
    }
  }

  private record Snapshot(long globalSum, int accountsChecked, List<AccountDrift> drifted) {}
}
