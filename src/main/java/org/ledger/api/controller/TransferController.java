package org.ledger.api.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.ledger.api.dto.CreateTransferRequest;
import org.ledger.api.dto.TransferResponse;
import org.ledger.transfer.TransferResult;
import org.ledger.transfer.TransferService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

  private final TransferService transferService;

  public TransferController(TransferService transferService) {
    this.transferService = transferService;
  }

  /**
   * {@code Idempotency-Key} is required but not yet enforced — see SPEC 0003. A duplicate request
   * currently double-applies the transfer.
   */
  @PostMapping
  public ResponseEntity<TransferResponse> createTransfer(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateTransferRequest request) {
    TransferResult result =
        transferService.execute(
            request.fromAccountId(), request.toAccountId(), request.amount(), request.currency());
    TransferResponse body = TransferResponse.from(result);
    return ResponseEntity.created(URI.create("/api/v1/transfers/" + body.id())).body(body);
  }

  @GetMapping("/{transferId}")
  public TransferResponse getTransfer(@PathVariable UUID transferId) {
    return TransferResponse.from(transferService.getTransfer(transferId));
  }
}
