package org.ledger.transfer;

import java.util.Optional;
import java.util.UUID;
import org.ledger.account.AccountNotFoundException;
import org.ledger.db.AccountRepository;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.db.TransferRepository;
import org.ledger.db.generated.tables.records.AccountsRecord;
import org.ledger.db.generated.tables.records.TransfersRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts a balanced transfer in one ACID transaction. This is the sole write-path
 * {@code @Transactional} method in the system (planning/03-system-design.md §1.3).
 *
 * <p>Uses a plain read-then-write on account balances: no locking. This is knowingly racy; SPEC
 * 0004 replaces it with an explicit {@code ConcurrencyStrategy}. Do not add ad-hoc locking here.
 */
@Service
public class TransferService {

  private final AccountRepository accountRepository;
  private final TransferRepository transferRepository;
  private final LedgerEntryRepository ledgerEntryRepository;

  public TransferService(
      AccountRepository accountRepository,
      TransferRepository transferRepository,
      LedgerEntryRepository ledgerEntryRepository) {
    this.accountRepository = accountRepository;
    this.transferRepository = transferRepository;
    this.ledgerEntryRepository = ledgerEntryRepository;
  }

  @Transactional
  public TransferResult execute(
      UUID fromAccountId, UUID toAccountId, long amountMinor, String currency) {
    AccountsRecord from =
        accountRepository
            .findById(fromAccountId)
            .orElseThrow(() -> new AccountNotFoundException(fromAccountId));
    AccountsRecord to =
        accountRepository
            .findById(toAccountId)
            .orElseThrow(() -> new AccountNotFoundException(toAccountId));

    // Balance guard BEFORE any write: rejection needs no rollback of already-written entries.
    // The DB CHECK(balance >= min_balance) is the backstop, not the primary guard.
    if (from.getBalance() - amountMinor < from.getMinBalance()) {
      throw new InsufficientFundsException(
          fromAccountId, from.getBalance() - from.getMinBalance(), amountMinor, currency);
    }

    TransfersRecord transfer =
        transferRepository.insertPending(fromAccountId, toAccountId, amountMinor, currency);
    ledgerEntryRepository.insertBalancedPair(
        transfer.getId(), fromAccountId, toAccountId, amountMinor);
    accountRepository.applyDelta(fromAccountId, -amountMinor);
    accountRepository.applyDelta(toAccountId, amountMinor);
    transferRepository.markCompleted(transfer.getId());

    return new TransferResult(transfer.getId(), TransferStatus.COMPLETED);
  }

  @Transactional(readOnly = true)
  public Optional<TransferResult> getTransfer(UUID transferId) {
    return transferRepository
        .findById(transferId)
        .map(t -> new TransferResult(t.getId(), TransferStatus.valueOf(t.getStatus())));
  }
}
