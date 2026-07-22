package org.ledger.saga;

import java.util.List;
import org.ledger.db.SagaRepository;
import org.ledger.db.generated.tables.records.SagasRecord;
import org.ledger.infrastructure.TomcatConnectorGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Recovers every non-terminal saga on startup, before the paused Tomcat connector ({@link
 * TomcatConnectorGate}) is resumed and the server starts accepting traffic.
 *
 * <p>A single bad saga is logged and skipped rather than aborting the whole run -- one saga's
 * unrecoverable state must not hold every other, healthy saga hostage. The connector is resumed in
 * a {@code finally} around that loop, so it always runs once every saga has been attempted.
 *
 * <p>If listing recoverable sagas itself fails (the DB is unreachable, say), that is treated as
 * genuinely fatal: the connector is deliberately left paused. An application that never accepts
 * traffic is the correct failure mode here -- silently resuming would let requests race a system
 * that cannot even enumerate its own in-flight sagas.
 */
@Component
@Order
public class SagaRecoveryRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SagaRecoveryRunner.class);

  private final SagaRepository sagaRepository;
  private final SagaOrchestrator orchestrator;
  private final TomcatConnectorGate gate;

  public SagaRecoveryRunner(
      SagaRepository sagaRepository, SagaOrchestrator orchestrator, TomcatConnectorGate gate) {
    this.sagaRepository = sagaRepository;
    this.orchestrator = orchestrator;
    this.gate = gate;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<SagasRecord> recoverable;
    try {
      recoverable = sagaRepository.findRecoverable();
    } catch (RuntimeException e) {
      log.error("Fatal error listing recoverable sagas; refusing to accept traffic", e);
      return;
    }

    log.info("Saga recovery: reconciling {} saga(s) before accepting traffic", recoverable.size());
    try {
      for (SagasRecord saga : recoverable) {
        try {
          orchestrator.recover(saga);
        } catch (RuntimeException e) {
          log.error("Saga recovery failed for saga {}", saga.getId(), e);
        }
      }
    } finally {
      gate.resume();
    }
  }
}
