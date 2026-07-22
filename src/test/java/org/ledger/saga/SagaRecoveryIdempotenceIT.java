package org.ledger.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountService;
import org.ledger.db.SagaRepository;
import org.ledger.db.generated.tables.records.SagaStepsRecord;
import org.ledger.db.generated.tables.records.SagasRecord;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SPEC 0006 -- running recovery twice against the same interrupted-saga DB state is a no-op the
 * second time.
 */
class SagaRecoveryIdempotenceIT extends AbstractPostgresIT {

  @Autowired private SagaOrchestrator sagaOrchestrator;
  @Autowired private SagaRepository sagaRepository;
  @Autowired private AccountService accountService;

  @Test
  void secondRecoveryRunChangesNothing() {
    UUID a = accountService.createAccount("a", "USD", 0).id();
    UUID b = accountService.createAccount("b", "USD", 0).id();
    UUID c = accountService.createAccount("c", "USD", 0).id();
    seedInitialBalance(a, 3_000L);

    List<SagaLeg> legs = List.of(new SagaLeg(0, a, b, 1_000L), new SagaLeg(1, b, c, 5_000L));
    SagaDefinition definition =
        new SagaDefinition(SagaDefinition.TYPE_MULTI_LEG_TRANSFER, "USD", null, legs);

    // Crash right after leg 0 commits -- leg 1 never even starts, so its own forward() failure
    // path (which would otherwise compensate synchronously) never runs either.
    sagaOrchestrator.setStepCommittedHookForTesting(
        (ctx, stepIndex) -> {
          throw new RuntimeException("simulated crash after step " + stepIndex);
        });
    try {
      assertThatThrownBy(() -> sagaOrchestrator.execute(definition))
          .isInstanceOf(RuntimeException.class);
    } finally {
      sagaOrchestrator.setStepCommittedHookForTesting(null);
    }

    UUID sagaId = sagaRepository.findRecoverable().get(0).getId();

    sagaOrchestrator.recover(sagaRepository.findById(sagaId).orElseThrow());

    SagasRecord afterFirstRun = sagaRepository.findById(sagaId).orElseThrow();
    assertThat(afterFirstRun.getState()).isEqualTo("COMPENSATED");
    List<SagaStepsRecord> stepsAfterFirstRun = sagaRepository.findSteps(sagaId);
    long ledgerEntriesAfterFirstRun = dsl.fetchCount(DSL.table("ledger_entries"));

    sagaOrchestrator.recover(afterFirstRun);

    SagasRecord afterSecondRun = sagaRepository.findById(sagaId).orElseThrow();
    List<SagaStepsRecord> stepsAfterSecondRun = sagaRepository.findSteps(sagaId);
    long ledgerEntriesAfterSecondRun = dsl.fetchCount(DSL.table("ledger_entries"));

    assertThat(afterSecondRun.getState()).isEqualTo("COMPENSATED");
    assertThat(afterSecondRun.getUpdatedAt()).isEqualTo(afterFirstRun.getUpdatedAt());
    assertThat(ledgerEntriesAfterSecondRun).isEqualTo(ledgerEntriesAfterFirstRun);

    assertThat(stepsAfterSecondRun).hasSameSizeAs(stepsAfterFirstRun);
    for (int i = 0; i < stepsAfterFirstRun.size(); i++) {
      assertThat(stepsAfterSecondRun.get(i).getState())
          .isEqualTo(stepsAfterFirstRun.get(i).getState());
      assertThat(stepsAfterSecondRun.get(i).getUpdatedAt())
          .isEqualTo(stepsAfterFirstRun.get(i).getUpdatedAt());
    }
  }
}
