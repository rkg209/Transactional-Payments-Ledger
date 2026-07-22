package org.ledger.saga;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.jooq.JSONB;
import org.ledger.db.SagaRepository;
import org.ledger.db.TransferRepository;
import org.ledger.db.generated.tables.records.SagaStepsRecord;
import org.ledger.db.generated.tables.records.SagasRecord;
import org.ledger.transfer.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Coordinates a chain-of-legs saga (ADR 0008): {@link #execute} runs it forward, compensating on
 * failure; {@link #recover} reconciles an in-progress saga found on startup.
 *
 * <p>Holds no transaction across steps -- each state write is its own committed {@link
 * TransactionTemplate} call, "persist intent before acting." That absence of a spanning transaction
 * is what makes this a saga rather than a distributed transaction (SPEC 0006 design notes):
 * atomicity comes from compensation, not from held locks.
 */
@Service
public class SagaOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

  private final SagaRepository sagaRepository;
  private final TransferRepository transferRepository;
  private final TransferService transferService;
  private final TransactionTemplate ledgerTransactionTemplate;

  // NFR-20 meters, registered once against held fields (SPEC 0009).
  private final Counter sagasCompletedCounter;
  private final Counter sagasCompensatedCounter;
  private final Counter sagasFailedCounter;
  private final Timer sagaDurationTimer;

  /**
   * Test-only crash-simulation seam: invoked right after each step's forward commit, before the
   * next step starts. {@code SagaCrashRecoveryIT} installs a hook that throws, abandoning the
   * in-flight {@link #execute} call the way a real process kill would -- there is no in-process way
   * to simulate an actual JVM crash inside one Testcontainers-backed test.
   */
  private volatile BiConsumer<SagaContext, Integer> stepCommittedHook = (ctx, stepIndex) -> {};

  public SagaOrchestrator(
      SagaRepository sagaRepository,
      TransferRepository transferRepository,
      TransferService transferService,
      TransactionTemplate ledgerTransactionTemplate,
      MeterRegistry meterRegistry) {
    this.sagaRepository = sagaRepository;
    this.transferRepository = transferRepository;
    this.transferService = transferService;
    this.ledgerTransactionTemplate = ledgerTransactionTemplate;

    this.sagasCompletedCounter =
        meterRegistry.counter("ledger.sagas.total", "outcome", "completed");
    this.sagasCompensatedCounter =
        meterRegistry.counter("ledger.sagas.total", "outcome", "compensated");
    this.sagasFailedCounter = meterRegistry.counter("ledger.sagas.total", "outcome", "failed");
    this.sagaDurationTimer = meterRegistry.timer("ledger.saga.duration");
  }

  void setStepCommittedHookForTesting(BiConsumer<SagaContext, Integer> hook) {
    this.stepCommittedHook = hook == null ? (ctx, stepIndex) -> {} : hook;
  }

  public SagaResult execute(SagaDefinition definition) {
    long startNanos = System.nanoTime();
    try {
      return executeTimed(definition);
    } finally {
      sagaDurationTimer.record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
  }

  private SagaResult executeTimed(SagaDefinition definition) {
    SagasRecord sagaRow =
        ledgerTransactionTemplate.execute(
            status -> sagaRepository.insertSaga(definition.type(), definition.toJsonb()));
    UUID sagaId = sagaRow.getId();
    SagaContext ctx = new SagaContext(sagaId, definition.currency());
    List<SagaLeg> completedLegs = new ArrayList<>();

    for (SagaLeg leg : definition.legs()) {
      LegTransferStep step = new LegTransferStep(transferService, transferRepository, leg);
      ledgerTransactionTemplate.executeWithoutResult(
          status -> sagaRepository.insertStepInProgress(sagaId, leg.stepIndex(), step.stepType()));

      JsonNode forwardResult;
      try {
        forwardResult = step.forward(ctx);
      } catch (RuntimeException e) {
        compensate(sagaId, ctx, completedLegs);
        throw new SagaCompensatedException(sagaId, completedLegs.size());
      }

      JSONB forwardJsonb = SagaJson.toJsonb(forwardResult);
      ledgerTransactionTemplate.executeWithoutResult(
          status -> sagaRepository.markStepCompleted(sagaId, leg.stepIndex(), forwardJsonb));
      transitionState(sagaId, SagaState.STARTED, SagaState.STARTED, leg.stepIndex());
      completedLegs.add(leg);

      stepCommittedHook.accept(ctx, leg.stepIndex());
    }

    int lastIndex = definition.legs().size() - 1;
    transitionState(sagaId, SagaState.STARTED, SagaState.COMPLETED, lastIndex);
    sagasCompletedCounter.increment();

    return getSaga(sagaId);
  }

  /**
   * Steps that never completed a forward action (a step never started, or its forward never
   * committed) are never compensated -- there is nothing to reverse.
   */
  private void compensate(UUID sagaId, SagaContext ctx, List<SagaLeg> completedLegs) {
    int boundary =
        completedLegs.isEmpty() ? 0 : completedLegs.get(completedLegs.size() - 1).stepIndex();
    transitionState(sagaId, SagaState.STARTED, SagaState.COMPENSATING, boundary);

    try {
      for (int i = completedLegs.size() - 1; i >= 0; i--) {
        SagaLeg leg = completedLegs.get(i);
        LegTransferStep step = new LegTransferStep(transferService, transferRepository, leg);
        step.compensate(ctx, null);
        ledgerTransactionTemplate.executeWithoutResult(
            status -> sagaRepository.markStepCompensated(sagaId, leg.stepIndex()));
      }
    } catch (RuntimeException e) {
      transitionState(sagaId, SagaState.COMPENSATING, SagaState.FAILED, boundary);
      sagasFailedCounter.increment();
      throw new SagaFailedException(sagaId);
    }

    transitionState(sagaId, SagaState.COMPENSATING, SagaState.COMPENSATED, boundary);
    sagasCompensatedCounter.increment();
  }

  /**
   * Reconciles one saga found by {@link SagaRepository#findRecoverable}. Never resumes forward
   * progress -- only completes a saga whose every leg actually committed, or unwinds one that
   * didn't (SPEC 0006 design notes). Idempotent: a step or saga-state row already at its target
   * state is left untouched, so running recovery twice against the same DB state writes nothing the
   * second time.
   */
  public void recover(SagasRecord sagaRow) {
    UUID sagaId = sagaRow.getId();
    SagaDefinition definition = SagaDefinition.fromJsonb(sagaRow.getPayload());
    SagaContext ctx = new SagaContext(sagaId, definition.currency());

    Map<Integer, SagaStepsRecord> steps = new HashMap<>();
    for (SagaStepsRecord r : sagaRepository.findSteps(sagaId)) {
      steps.put(r.getStepIndex(), r);
    }

    // Pass 1: disambiguate every IN_PROGRESS step. State is persisted before forward() runs, so an
    // IN_PROGRESS row is ambiguous by construction -- a lookup by the step's deterministic forward
    // key resolves it: found means the leg actually committed, not found means it never ran.
    for (SagaLeg leg : definition.legs()) {
      SagaStepsRecord stepRow = steps.get(leg.stepIndex());
      if (stepRow == null || !SagaStepState.IN_PROGRESS.name().equals(stepRow.getState())) {
        continue;
      }
      String forwardKey = LegTransferStep.forwardKey(sagaId, leg.stepIndex());
      transferRepository
          .findByIdempotencyKey(forwardKey)
          .ifPresent(
              transfer -> {
                JSONB forwardResult =
                    SagaJson.toJsonb(LegTransferStep.transferResultJson(transfer.getId()));
                ledgerTransactionTemplate.executeWithoutResult(
                    status ->
                        sagaRepository.markStepCompleted(sagaId, leg.stepIndex(), forwardResult));
                stepRow.setState(SagaStepState.COMPLETED.name());
              });
    }

    boolean allCompleted =
        definition.legs().stream()
            .allMatch(
                leg -> {
                  SagaStepsRecord r = steps.get(leg.stepIndex());
                  return r != null && SagaStepState.COMPLETED.name().equals(r.getState());
                });

    String sagaState = sagaRow.getState();
    int lastIndex = definition.legs().size() - 1;

    if (allCompleted) {
      if (!SagaState.COMPLETED.name().equals(sagaState)) {
        transitionState(sagaId, SagaState.valueOf(sagaState), SagaState.COMPLETED, lastIndex);
      }
      return;
    }

    if (!SagaState.COMPENSATING.name().equals(sagaState)
        && !SagaState.COMPENSATED.name().equals(sagaState)) {
      transitionState(
          sagaId, SagaState.valueOf(sagaState), SagaState.COMPENSATING, sagaRow.getCurrentStep());
      sagaState = SagaState.COMPENSATING.name();
    }

    List<SagaLeg> reversed = new ArrayList<>(definition.legs());
    Collections.reverse(reversed);
    for (SagaLeg leg : reversed) {
      SagaStepsRecord stepRow = steps.get(leg.stepIndex());
      if (stepRow == null || !SagaStepState.COMPLETED.name().equals(stepRow.getState())) {
        continue;
      }
      LegTransferStep step = new LegTransferStep(transferService, transferRepository, leg);
      step.compensate(ctx, null);
      ledgerTransactionTemplate.executeWithoutResult(
          status -> sagaRepository.markStepCompensated(sagaId, leg.stepIndex()));
      stepRow.setState(SagaStepState.COMPENSATED.name());
    }

    if (!SagaState.COMPENSATED.name().equals(sagaState)) {
      transitionState(
          sagaId, SagaState.valueOf(sagaState), SagaState.COMPENSATED, sagaRow.getCurrentStep());
    }
  }

  /** Persists a saga-state transition and emits the matching structured log event (NFR-19). */
  private void transitionState(UUID sagaId, SagaState from, SagaState to, int stepIndex) {
    ledgerTransactionTemplate.executeWithoutResult(
        status -> sagaRepository.updateSagaState(sagaId, to.name(), stepIndex));
    log.info("saga_transition sagaId={} from={} to={} stepIndex={}", sagaId, from, to, stepIndex);
  }

  public SagaResult getSaga(UUID sagaId) {
    SagasRecord sagaRow =
        sagaRepository.findById(sagaId).orElseThrow(() -> new SagaNotFoundException(sagaId));
    if (SagaState.FAILED.name().equals(sagaRow.getState())) {
      throw new SagaFailedException(sagaId);
    }
    SagaDefinition definition = SagaDefinition.fromJsonb(sagaRow.getPayload());
    List<SagaStepResult> stepResults =
        sagaRepository.findSteps(sagaId).stream().map(SagaOrchestrator::toStepResult).toList();
    return new SagaResult(
        sagaRow.getId(),
        sagaRow.getType(),
        SagaState.valueOf(sagaRow.getState()),
        sagaRow.getCurrentStep(),
        definition.currency(),
        definition.description(),
        stepResults,
        sagaRow.getCreatedAt(),
        sagaRow.getUpdatedAt());
  }

  private static SagaStepResult toStepResult(SagaStepsRecord r) {
    return new SagaStepResult(
        r.getId(),
        r.getSagaId(),
        r.getStepIndex(),
        r.getStepType(),
        SagaStepState.valueOf(r.getState()),
        SagaJson.toJsonNode(r.getForwardResult()),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
