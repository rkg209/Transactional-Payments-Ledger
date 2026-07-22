package org.ledger.db;

import static org.ledger.db.generated.tables.Transfers.TRANSFERS;

import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.ledger.db.generated.tables.records.TransfersRecord;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

  private final DSLContext dsl;

  public TransferRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public TransfersRecord insertPending(
      UUID fromAccountId,
      UUID toAccountId,
      long amountMinor,
      String currency,
      String idempotencyKey) {
    return dsl.insertInto(TRANSFERS)
        .set(TRANSFERS.FROM_ACCOUNT_ID, fromAccountId)
        .set(TRANSFERS.TO_ACCOUNT_ID, toAccountId)
        .set(TRANSFERS.AMOUNT_MINOR, amountMinor)
        .set(TRANSFERS.CURRENCY, currency)
        .set(TRANSFERS.STATUS, "PENDING")
        .set(TRANSFERS.IDEMPOTENCY_KEY, idempotencyKey)
        .returning()
        .fetchOne();
  }

  public void markCompleted(UUID transferId) {
    dsl.update(TRANSFERS)
        .set(TRANSFERS.STATUS, "COMPLETED")
        .set(TRANSFERS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(TRANSFERS.ID.eq(transferId))
        .execute();
  }

  public Optional<TransfersRecord> findById(UUID id) {
    return Optional.ofNullable(dsl.selectFrom(TRANSFERS).where(TRANSFERS.ID.eq(id)).fetchOne());
  }

  /**
   * Used by SPEC 0006's saga machinery: a pre-insert lookup by a deterministic per-step key, so a
   * retried forward/compensate finds the leg that already committed instead of racing the {@code
   * transfers_idempotency_key_uq} constraint (see {@code LegTransferStep}, {@code
   * SagaOrchestrator.recover}).
   */
  public Optional<TransfersRecord> findByIdempotencyKey(String idempotencyKey) {
    return Optional.ofNullable(
        dsl.selectFrom(TRANSFERS).where(TRANSFERS.IDEMPOTENCY_KEY.eq(idempotencyKey)).fetchOne());
  }
}
