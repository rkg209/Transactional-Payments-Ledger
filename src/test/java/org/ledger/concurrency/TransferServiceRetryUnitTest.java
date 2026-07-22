package org.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.db.LedgerEntryRepository;
import org.ledger.db.TransferRepository;
import org.ledger.db.generated.tables.records.AccountsRecord;
import org.ledger.db.generated.tables.records.TransfersRecord;
import org.ledger.infrastructure.LedgerConcurrencyProperties;
import org.ledger.transfer.OptimisticLockException;
import org.ledger.transfer.TransferService;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC 0004's retry loop, exercised without Postgres: the fake {@link TransactionTemplate} just
 * invokes the callback inline (rollback-on-conflict is Postgres's job, proven separately by the
 * real ITs), which makes the attempt count and exhaustion behavior deterministic instead of racing
 * real transactions for it.
 */
class TransferServiceRetryUnitTest {

  private final TransferRepository transferRepository = mock(TransferRepository.class);
  private final LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
  private final ConcurrencyStrategy strategy = mock(ConcurrencyStrategy.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  {
    when(strategy.name()).thenReturn("mock");
  }

  private final TransactionTemplate inlineTransactionTemplate =
      new TransactionTemplate() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
          return action.doInTransaction(mock(TransactionStatus.class));
        }
      };

  @Test
  void retriesOnceThenSucceeds() {
    UUID fromId = UUID.randomUUID();
    UUID toId = UUID.randomUUID();
    LockedAccounts locked = lockedAccountsFor(fromId, toId);

    when(strategy.lockAndLoad(fromId, toId)).thenReturn(locked);
    // First attempt's debit loses the race; the second attempt (fresh transaction) succeeds.
    doThrow(new ConcurrencyConflictException(fromId))
        .doNothing()
        .when(strategy)
        .applyDelta(eq(locked), eq(fromId), anyLong());

    TransfersRecord transferRecord = transferRecord(fromId, toId);
    when(transferRepository.insertPending(any(), any(), anyLong(), anyString(), anyString()))
        .thenReturn(transferRecord);
    when(transferRepository.findById(transferRecord.getId()))
        .thenReturn(Optional.of(transferRecord));

    TransferService service =
        new TransferService(
            transferRepository,
            ledgerEntryRepository,
            strategy,
            inlineTransactionTemplate,
            new LedgerConcurrencyProperties(5, 1),
            meterRegistry);

    service.execute(fromId, toId, 100L, "USD", "retry-unit-key");

    verify(strategy, times(2)).lockAndLoad(fromId, toId);
    verify(transferRepository, times(2))
        .insertPending(any(), any(), anyLong(), anyString(), anyString());
  }

  @Test
  void exhaustsAfterMaxAttemptsAndThrows() {
    UUID fromId = UUID.randomUUID();
    UUID toId = UUID.randomUUID();
    LockedAccounts locked = lockedAccountsFor(fromId, toId);

    when(strategy.lockAndLoad(fromId, toId)).thenReturn(locked);
    doThrow(new ConcurrencyConflictException(fromId))
        .when(strategy)
        .applyDelta(eq(locked), eq(fromId), anyLong());

    when(transferRepository.insertPending(any(), any(), anyLong(), anyString(), anyString()))
        .thenReturn(transferRecord(fromId, toId));

    TransferService service =
        new TransferService(
            transferRepository,
            ledgerEntryRepository,
            strategy,
            inlineTransactionTemplate,
            new LedgerConcurrencyProperties(1, 1),
            meterRegistry);

    assertThatThrownBy(() -> service.execute(fromId, toId, 100L, "USD", "retry-unit-key-exhausted"))
        .isInstanceOf(OptimisticLockException.class)
        .satisfies(e -> assertThat(((OptimisticLockException) e).attempts()).isEqualTo(1));

    verify(strategy, times(1)).lockAndLoad(fromId, toId);
  }

  private static LockedAccounts lockedAccountsFor(UUID fromId, UUID toId) {
    return new LockedAccounts(account(fromId), account(toId));
  }

  private static AccountsRecord account(UUID id) {
    OffsetDateTime now = OffsetDateTime.now();
    return new AccountsRecord(id, "acct-" + id, "USD", 0L, 1_000L, 0L, now, now);
  }

  private static TransfersRecord transferRecord(UUID fromId, UUID toId) {
    OffsetDateTime now = OffsetDateTime.now();
    TransfersRecord record = new TransfersRecord();
    record.setId(UUID.randomUUID());
    record.setFromAccountId(fromId);
    record.setToAccountId(toId);
    record.setAmountMinor(100L);
    record.setCurrency("USD");
    record.setStatus("COMPLETED");
    record.setCreatedAt(now);
    record.setUpdatedAt(now);
    return record;
  }
}
