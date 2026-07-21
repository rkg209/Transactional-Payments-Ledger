package org.ledger.api.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.api.dto.AccountResponse;
import org.ledger.api.dto.BalanceResponse;
import org.ledger.api.dto.CreateAccountRequest;
import org.ledger.api.dto.PageResponse;
import org.ledger.api.dto.PaginationMeta;
import org.ledger.api.pagination.Cursor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Validated
public class AccountController {

  private final AccountService accountService;

  public AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  @PostMapping
  public ResponseEntity<AccountResponse> createAccount(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateAccountRequest request) {
    AccountResult result =
        accountService.createAccount(
            request.name(),
            request.currency(),
            request.minBalance() == null ? 0 : request.minBalance());
    AccountResponse body = AccountResponse.from(result);
    return ResponseEntity.created(URI.create("/api/v1/accounts/" + body.id())).body(body);
  }

  @GetMapping
  public PageResponse<AccountResponse> listAccounts(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
      @RequestParam(required = false) String cursor) {
    Cursor decoded = cursor == null ? null : Cursor.decode(cursor);
    List<AccountResult> page =
        accountService.listAccounts(
            decoded == null ? null : decoded.createdAt(),
            decoded == null ? null : decoded.id(),
            limit);

    boolean hasMore = page.size() > limit;
    List<AccountResult> pageResults = hasMore ? page.subList(0, limit) : page;
    String nextCursor =
        hasMore
            ? new Cursor(
                    pageResults.get(pageResults.size() - 1).createdAt(),
                    pageResults.get(pageResults.size() - 1).id())
                .encode()
            : null;

    List<AccountResponse> data = pageResults.stream().map(AccountResponse::from).toList();
    return new PageResponse<>(data, new PaginationMeta(limit, nextCursor, hasMore));
  }

  @GetMapping("/{accountId}")
  public AccountResponse getAccount(@PathVariable UUID accountId) {
    return AccountResponse.from(accountService.getAccount(accountId));
  }

  @GetMapping("/{accountId}/balance")
  public BalanceResponse getBalance(@PathVariable UUID accountId) {
    return BalanceResponse.from(accountService.getAccount(accountId));
  }
}
