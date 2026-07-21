package org.ledger.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.ledger.db.AccountRepository;
import org.ledger.db.generated.tables.records.AccountsRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

  private final AccountRepository accountRepository;

  public AccountService(AccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  @Transactional
  public AccountResult createAccount(String name, String currency, long minBalance) {
    return toResult(accountRepository.insert(name, currency, minBalance));
  }

  @Transactional(readOnly = true)
  public AccountResult getAccount(UUID accountId) {
    return accountRepository
        .findById(accountId)
        .map(AccountService::toResult)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
  }

  /**
   * Fetches one keyset page. {@code afterCreatedAt}/{@code afterId} are both null for the first
   * page. Returns up to {@code limit + 1} results; the caller uses the extra row to compute {@code
   * hasMore} without a separate count query.
   */
  @Transactional(readOnly = true)
  public List<AccountResult> listAccounts(OffsetDateTime afterCreatedAt, UUID afterId, int limit) {
    return accountRepository.findPage(afterCreatedAt, afterId, limit).stream()
        .map(AccountService::toResult)
        .toList();
  }

  private static AccountResult toResult(AccountsRecord record) {
    return new AccountResult(
        record.getId(),
        record.getName(),
        record.getCurrency(),
        record.getMinBalance(),
        record.getBalance(),
        record.getVersion(),
        record.getCreatedAt(),
        record.getUpdatedAt());
  }
}
