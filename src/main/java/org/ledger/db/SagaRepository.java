package org.ledger.db;

import static org.ledger.db.generated.tables.SagaSteps.SAGA_STEPS;
import static org.ledger.db.generated.tables.Sagas.SAGAS;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.ledger.db.generated.tables.records.SagaStepsRecord;
import org.ledger.db.generated.tables.records.SagasRecord;
import org.springframework.stereotype.Repository;

/**
 * Persistence for SPEC 0006's saga bookkeeping. States are plain strings, not the {@code
 * org.ledger.saga} enums -- {@code ArchitectureFitnessTest.db_must_not_depend_on_domain_logic}
 * forbids this package from depending on {@code org.ledger.saga..}, the same discipline {@code
 * TransferRepository} already follows for {@code TransferStatus}.
 */
@Repository
public class SagaRepository {

  private final DSLContext dsl;

  public SagaRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public SagasRecord insertSaga(String type, JSONB payload) {
    return dsl.insertInto(SAGAS)
        .set(SAGAS.TYPE, type)
        .set(SAGAS.STATE, "STARTED")
        .set(SAGAS.CURRENT_STEP, 0)
        .set(SAGAS.PAYLOAD, payload)
        .returning()
        .fetchOne();
  }

  public void updateSagaState(UUID sagaId, String state, int currentStep) {
    dsl.update(SAGAS)
        .set(SAGAS.STATE, state)
        .set(SAGAS.CURRENT_STEP, currentStep)
        .set(SAGAS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(SAGAS.ID.eq(sagaId))
        .execute();
  }

  public SagaStepsRecord insertStepInProgress(UUID sagaId, int stepIndex, String stepType) {
    return dsl.insertInto(SAGA_STEPS)
        .set(SAGA_STEPS.SAGA_ID, sagaId)
        .set(SAGA_STEPS.STEP_INDEX, stepIndex)
        .set(SAGA_STEPS.STEP_TYPE, stepType)
        .set(SAGA_STEPS.STATE, "IN_PROGRESS")
        .returning()
        .fetchOne();
  }

  public void markStepCompleted(UUID sagaId, int stepIndex, JSONB forwardResult) {
    dsl.update(SAGA_STEPS)
        .set(SAGA_STEPS.STATE, "COMPLETED")
        .set(SAGA_STEPS.FORWARD_RESULT, forwardResult)
        .set(SAGA_STEPS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(SAGA_STEPS.SAGA_ID.eq(sagaId))
        .and(SAGA_STEPS.STEP_INDEX.eq(stepIndex))
        .execute();
  }

  public void markStepCompensated(UUID sagaId, int stepIndex) {
    dsl.update(SAGA_STEPS)
        .set(SAGA_STEPS.STATE, "COMPENSATED")
        .set(SAGA_STEPS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(SAGA_STEPS.SAGA_ID.eq(sagaId))
        .and(SAGA_STEPS.STEP_INDEX.eq(stepIndex))
        .execute();
  }

  public Optional<SagasRecord> findById(UUID id) {
    return Optional.ofNullable(dsl.selectFrom(SAGAS).where(SAGAS.ID.eq(id)).fetchOne());
  }

  public List<SagaStepsRecord> findSteps(UUID sagaId) {
    return dsl.selectFrom(SAGA_STEPS)
        .where(SAGA_STEPS.SAGA_ID.eq(sagaId))
        .orderBy(SAGA_STEPS.STEP_INDEX.asc())
        .fetch();
  }

  /**
   * Every saga {@code SagaRecoveryRunner} must reconcile on startup: not yet in a terminal state.
   * The predicate deliberately matches {@code idx_sagas_recovery}'s partial-index condition ({@code
   * state NOT IN ('COMPLETED', 'COMPENSATED')}) closely enough for the planner to use it -- {@code
   * FAILED} sagas are excluded here (they need a human, not another recovery pass) but that only
   * narrows the scan, it doesn't stop the index from being usable.
   */
  public List<SagasRecord> findRecoverable() {
    return dsl.selectFrom(SAGAS)
        .where(SAGAS.STATE.in("STARTED", "COMPENSATING"))
        .orderBy(SAGAS.CREATED_AT.asc())
        .fetch();
  }
}
