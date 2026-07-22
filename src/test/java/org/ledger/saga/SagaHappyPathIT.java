package org.ledger.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountService;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.support.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;

/** SPEC 0006 -- a multi-leg chain where every leg succeeds. */
class SagaHappyPathIT extends AbstractPostgresIT {

  @Autowired private SagaOrchestrator sagaOrchestrator;
  @Autowired private AccountService accountService;
  @Autowired private LedgerEntryRepository ledgerEntryRepository;

  @Test
  void allLegsSucceedAndTheSagaCompletes() {
    UUID a = accountService.createAccount("a", "USD", 0).id();
    UUID b = accountService.createAccount("b", "USD", 0).id();
    UUID c = accountService.createAccount("c", "USD", 0).id();
    seedInitialBalance(a, 10_000L);

    SagaDefinition definition =
        new SagaDefinition(
            SagaDefinition.TYPE_MULTI_LEG_TRANSFER,
            "USD",
            "Split payment: A pays B and C",
            List.of(new SagaLeg(0, a, b, 4_000L), new SagaLeg(1, b, c, 1_500L)));

    SagaResult result = sagaOrchestrator.execute(definition);

    assertThat(result.state()).isEqualTo(SagaState.COMPLETED);
    assertThat(result.currentStep()).isEqualTo(1);
    assertThat(result.steps()).hasSize(2);
    assertThat(result.steps())
        .allSatisfy(step -> assertThat(step.state()).isEqualTo(SagaStepState.COMPLETED));

    assertThat(accountService.getAccount(a).balance()).isEqualTo(6_000L);
    assertThat(accountService.getAccount(b).balance()).isEqualTo(2_500L);
    assertThat(accountService.getAccount(c).balance()).isEqualTo(1_500L);
    assertThat(ledgerEntryRepository.globalEntrySum()).isZero();

    SagaResult fetched = sagaOrchestrator.getSaga(result.id());
    assertThat(fetched.state()).isEqualTo(SagaState.COMPLETED);
    assertThat(fetched.steps()).hasSize(2);
  }
}
