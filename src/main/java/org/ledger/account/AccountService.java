package org.ledger.account;

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
