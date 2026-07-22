package org.ledger.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountService;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.db.SagaRepository;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;

/** SPEC 0006 -- a mid-saga failure compensates every prior-completed leg in reverse. */
class SagaCompensationIT extends AbstractPostgresIT {

  @Autowired private SagaOrchestrator sagaOrchestrator;
  @Autowired private SagaRepository sagaRepository;
  @Autowired private AccountService accountService;
  @Autowired private LedgerEntryRepository ledgerEntryRepository;

  @Test
  void midChainFailureCompensatesCompletedLegsAndRestoresBalances() {
    UUID a = accountService.createAccount("a", "USD", 0).id();
    UUID b = accountService.createAccount("b", "USD", 0).id();
    UUID c = accountService.createAccount("c", "USD", 0).id();
    seedInitialBalance(a, 1_000L);

    // Leg 0 (A->B, 1000) succeeds; leg 1 (B->C, 5000) fails -- B only received 1000.
    SagaDefinition definition =
        new SagaDefinition(
            SagaDefinition.TYPE_MULTI_LEG_TRANSFER,
            "USD",
            null,
            List.of(new SagaLeg(0, a, b, 1_000L), new SagaLeg(1, b, c, 5_000L)));

    SagaCompensatedException thrown =
        catchThrowableOfType(
            () -> sagaOrchestrator.execute(definition), SagaCompensatedException.class);
    assertThat(thrown).isNotNull();
    assertThat(thrown.compensatedSteps()).isEqualTo(1);

    UUID sagaId = thrown.sagaId();
    assertThat(sagaOrchestrator.getSaga(sagaId).state()).isEqualTo(SagaState.COMPENSATED);

    var sagaRow = sagaRepository.findById(sagaId).orElseThrow();
    assertThat(sagaRow.getState()).isEqualTo("COMPENSATED");
    assertThat(sagaRepository.findRecoverable()).isEmpty();

    var steps = sagaRepository.findSteps(sagaId);
    assertThat(steps.get(0).getState()).isEqualTo("COMPENSATED");
    assertThat(steps.get(1).getState()).isEqualTo("IN_PROGRESS");

    assertThat(accountService.getAccount(a).balance()).isEqualTo(1_000L);
    assertThat(accountService.getAccount(b).balance()).isZero();
    assertThat(accountService.getAccount(c).balance()).isZero();
    assertThat(ledgerEntryRepository.globalEntrySum()).isZero();
  }
}
