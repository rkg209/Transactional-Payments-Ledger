package org.ledger.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.ledger.account.AccountService;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.db.SagaRepository;
import org.ledger.db.generated.tables.records.SagasRecord;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SPEC 0006's headline test: killing the process at every step index and recovering must leave the
 * saga in exactly one terminal state, with Σ = 0. A real process kill isn't practical inside one
 * Testcontainers-backed JVM, so a test-only hook ({@code
 * SagaOrchestrator.setStepCommittedHookForTesting}) throws right after a chosen step's forward
 * commits, abandoning the in-flight {@code execute()} call the way a real crash would -- nothing
 * downstream of that point (including compensation) runs, exactly as if the process had died there.
 */
class SagaCrashRecoveryIT extends AbstractPostgresIT {

  @Autowired private SagaOrchestrator sagaOrchestrator;
  @Autowired private SagaRepository sagaRepository;
  @Autowired private AccountService accountService;
  @Autowired private LedgerEntryRepository ledgerEntryRepository;

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void killingAtEveryStepIndexRecoversToATerminalStateAndConserves(int crashAfterStepIndex) {
    UUID a = accountService.createAccount("a", "USD", 0).id();
    UUID b = accountService.createAccount("b", "USD", 0).id();
    UUID c = accountService.createAccount("c", "USD", 0).id();
    UUID d = accountService.createAccount("d", "USD", 0).id();
    seedInitialBalance(a, 3_000L);

    List<SagaLeg> legs =
        List.of(
            new SagaLeg(0, a, b, 1_000L),
            new SagaLeg(1, b, c, 1_000L),
            new SagaLeg(2, c, d, 1_000L));
    SagaDefinition definition =
        new SagaDefinition(SagaDefinition.TYPE_MULTI_LEG_TRANSFER, "USD", null, legs);

    sagaOrchestrator.setStepCommittedHookForTesting(
        (ctx, stepIndex) -> {
          if (stepIndex == crashAfterStepIndex) {
            throw new RuntimeException("simulated crash after step " + stepIndex);
          }
        });
    try {
      assertThatThrownBy(() -> sagaOrchestrator.execute(definition))
          .isInstanceOf(RuntimeException.class);
    } finally {
      sagaOrchestrator.setStepCommittedHookForTesting(null);
    }

    List<SagasRecord> recoverable = sagaRepository.findRecoverable();
    assertThat(recoverable).hasSize(1);
    SagasRecord sagaRow = recoverable.get(0);

    sagaOrchestrator.recover(sagaRow);

    SagasRecord after = sagaRepository.findById(sagaRow.getId()).orElseThrow();
    if (crashAfterStepIndex == legs.size() - 1) {
      assertThat(after.getState()).isEqualTo("COMPLETED");
    } else {
      assertThat(after.getState()).isEqualTo("COMPENSATED");
    }
    assertThat(ledgerEntryRepository.globalEntrySum()).isZero();
  }
}
