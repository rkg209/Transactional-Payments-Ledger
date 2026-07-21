package org.ledger.transfer;

import java.sql.SQLException;
import java.util.UUID;
import org.ledger.account.AccountNotFoundException;
import org.ledger.concurrency.ConcurrencyConflictException;
import org.ledger.concurrency.ConcurrencyStrategy;
import org.ledger.concurrency.LockedAccounts;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.db.TransferRepository;
import org.ledger.db.generated.tables.records.AccountsRecord;
import org.ledger.db.generated.tables.records.TransfersRecord;
import org.ledger.infrastructure.LedgerConcurrencyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts a balanced transfer. {@link #execute} is a bounded retry loop around a single {@link
 * TransactionTemplate} transaction (ADR 0006) — retry lives outside the transaction, isolation and
 * locking discipline live inside it, both visible at this call site rather than behind
 * {@code @Transactional} annotations or AOP.
 */
@Service
public class TransferService {

  private static final Logger log = LoggerFactory.getLogger(TransferService.class);

  /**
   * §3.2 INVALID_AMOUNT's upper bound. A documented ceiling, not an arbitrary long overflow guard.
   */
  public static final long MAX_TRANSFER_AMOUNT_MINOR = 999_999_999_999L;

  private static final String SQLSTATE_SERIALIZATION_FAILURE = "40001";
  private static final String SQLSTATE_DEADLOCK_DETECTED = "40P01";

  private final TransferRepository transferRepository;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final ConcurrencyStrategy concurrencyStrategy;
  private final TransactionTemplate ledgerTransactionTemplate;
  private final LedgerConcurrencyProperties concurrencyProperties;

  public TransferService(
      TransferRepository transferRepository,
      LedgerEntryRepository ledgerEntryRepository,
      ConcurrencyStrategy concurrencyStrategy,
      TransactionTemplate ledgerTransactionTemplate,
      LedgerConcurrencyProperties concurrencyProperties) {
    this.transferRepository = transferRepository;
    this.ledgerEntryRepository = ledgerEntryRepository;
    this.concurrencyStrategy = concurrencyStrategy;
    this.ledgerTransactionTemplate = ledgerTransactionTemplate;
    this.concurrencyProperties = concurrencyProperties;
  }

  public TransferResult execute(
      UUID fromAccountId,
      UUID toAccountId,
      long amountMinor,
      String currency,
      String idempotencyKey) {
    int maxAttempts = concurrencyProperties.maxAttempts();
    for (int attempt = 1; ; attempt++) {
      try {
        return ledgerTransactionTemplate.execute(
            status ->
                executeOnce(fromAccountId, toAccountId, amountMinor, currency, idempotencyKey));
      } catch (RuntimeException e) {
        if (!isRetryable(e)) {
          throw e;
        }
        if (attempt >= maxAttempts) {
          throw new OptimisticLockException(attempt);
        }
        log.info(
            "Retrying transfer {}->{} after conflict, attempt {}/{}",
            fromAccountId,
            toAccountId,
            attempt,
            maxAttempts);
        backoff(attempt, concurrencyProperties.backoffBaseMs());
      }
    }
  }

  private TransferResult executeOnce(
      UUID fromAccountId,
      UUID toAccountId,
      long amountMinor,
      String currency,
      String idempotencyKey) {
    if (fromAccountId.equals(toAccountId)) {
      throw new SameAccountException();
    }
    if (amountMinor <= 0 || amountMinor > MAX_TRANSFER_AMOUNT_MINOR) {
      throw new InvalidAmountException(amountMinor);
    }

    LockedAccounts locked = concurrencyStrategy.lockAndLoad(fromAccountId, toAccountId);
    if (locked.from() == null) {
      throw new AccountNotFoundException(fromAccountId);
    }
    if (locked.to() == null) {
      throw new AccountNotFoundException(toAccountId);
    }
    AccountsRecord from = locked.from();
    AccountsRecord to = locked.to();

    if (!from.getCurrency().equals(to.getCurrency())
        || !from.getCurrency().equals(currency)
        || !to.getCurrency().equals(currency)) {
      throw new CurrencyMismatchException(from.getCurrency(), to.getCurrency());
    }

    // Balance guard BEFORE any write: rejection needs no rollback of already-written entries.
    // The DB CHECK(balance >= min_balance) is the backstop, not the primary guard.
    if (from.getBalance() - amountMinor < from.getMinBalance()) {
      throw new InsufficientFundsException(
          fromAccountId, from.getBalance() - from.getMinBalance(), amountMinor, currency);
    }

    TransfersRecord transfer =
        transferRepository.insertPending(
            fromAccountId, toAccountId, amountMinor, currency, idempotencyKey);
    ledgerEntryRepository.insertBalancedPair(
        transfer.getId(), fromAccountId, toAccountId, amountMinor);
    // Applied in ascending id order: the optimistic strategy takes no lock upfront, so the row
    // lock Postgres implicitly takes for each UPDATE is only acquired here. Applying "from" first
    // regardless of id would let two opposite-direction transfers each hold one row and wait on
    // the other -- a real Postgres deadlock, not merely a CAS conflict (ADR 0006). Java's
    // UUID.compareTo is signed and disagrees with Postgres's unsigned uuid ordering (used by
    // findOrdered/findOrderedForUpdate) whenever the high bit of the most-significant 64 bits
    // differs -- harmless today because each strategy's conflicting locks all use one ordering
    // consistently (optimistic: only this Java comparison; pessimistic: only the single
    // ORDER BY id statement), but the two must never be mixed for the same lock set.
    if (fromAccountId.compareTo(toAccountId) < 0) {
      concurrencyStrategy.applyDelta(locked, fromAccountId, -amountMinor);
      concurrencyStrategy.applyDelta(locked, toAccountId, amountMinor);
    } else {
      concurrencyStrategy.applyDelta(locked, toAccountId, amountMinor);
      concurrencyStrategy.applyDelta(locked, fromAccountId, -amountMinor);
    }
    transferRepository.markCompleted(transfer.getId());

    return getTransfer(transfer.getId());
  }

  private static boolean isRetryable(RuntimeException e) {
    if (e instanceof ConcurrencyConflictException) {
      return true;
    }
    // Spring's DataAccessException chain wraps the driver's SQLException arbitrarily deep;
    // walk the whole cause chain rather than assuming a fixed depth.
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlException) {
        String sqlState = sqlException.getSQLState();
        if (SQLSTATE_SERIALIZATION_FAILURE.equals(sqlState)
            || SQLSTATE_DEADLOCK_DETECTED.equals(sqlState)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void backoff(int attempt, long backoffBaseMs) {
    long backoffMs = Math.min(backoffBaseMs * (1L << (attempt - 1)), 800L);
    try {
      Thread.sleep(backoffMs);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(ie);
    }
  }

  @Transactional(readOnly = true)
  public TransferResult getTransfer(UUID transferId) {
    return transferRepository
        .findById(transferId)
        .map(TransferService::toResult)
        .orElseThrow(() -> new TransferNotFoundException(transferId));
  }

  private static TransferResult toResult(TransfersRecord record) {
    return new TransferResult(
        record.getId(),
        record.getFromAccountId(),
        record.getToAccountId(),
        record.getAmountMinor(),
        record.getCurrency(),
        TransferStatus.valueOf(record.getStatus()),
        record.getCreatedAt(),
        record.getUpdatedAt());
  }
}
