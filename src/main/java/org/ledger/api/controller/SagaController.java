package org.ledger.api.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.ledger.api.dto.CreateSagaTransferRequest;
import org.ledger.api.dto.SagaResponse;
import org.ledger.api.dto.SagaStepRequest;
import org.ledger.saga.SagaDefinition;
import org.ledger.saga.SagaLeg;
import org.ledger.saga.SagaOrchestrator;
import org.ledger.saga.SagaResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SagaController {

  private final SagaOrchestrator sagaOrchestrator;

  public SagaController(SagaOrchestrator sagaOrchestrator) {
    this.sagaOrchestrator = sagaOrchestrator;
  }

  @PostMapping("/transfers/saga")
  public ResponseEntity<SagaResponse> createSaga(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateSagaTransferRequest request) {
    SagaResult result = sagaOrchestrator.execute(toDefinition(request));
    SagaResponse body = SagaResponse.from(result);
    return ResponseEntity.created(URI.create("/api/v1/sagas/" + body.id())).body(body);
  }

  @GetMapping("/sagas/{sagaId}")
  public SagaResponse getSaga(@PathVariable UUID sagaId) {
    return SagaResponse.from(sagaOrchestrator.getSaga(sagaId));
  }

  /**
   * Pairs the request's flat {@code (DEBIT, CREDIT)} step list into {@link SagaLeg}s --
   * {@code @ValidSagaSteps} has already guaranteed even length, alternation, equal amounts, and
   * differing accounts per pair, so this is pure reshaping, not re-validation.
   */
  private static SagaDefinition toDefinition(CreateSagaTransferRequest request) {
    List<SagaStepRequest> steps = request.steps();
    List<SagaLeg> legs = new ArrayList<>(steps.size() / 2);
    for (int i = 0; i < steps.size(); i += 2) {
      SagaStepRequest debit = steps.get(i);
      SagaStepRequest credit = steps.get(i + 1);
      legs.add(new SagaLeg(legs.size(), debit.accountId(), credit.accountId(), debit.amount()));
    }
    return new SagaDefinition(
        SagaDefinition.TYPE_MULTI_LEG_TRANSFER, request.currency(), request.description(), legs);
  }
}
