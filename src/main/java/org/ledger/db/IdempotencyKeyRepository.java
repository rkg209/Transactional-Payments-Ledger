package org.ledger.db;

import static org.ledger.db.generated.tables.IdempotencyKeys.IDEMPOTENCY_KEYS;

import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.ledger.db.generated.tables.records.IdempotencyKeysRecord;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * jOOQ-only access to {@code idempotency_keys}. {@link #tryClaim} is the concurrency primitive
 * (planning/04-database-schema.sql's comment on the table): a single {@code INSERT}, never {@code
 * SELECT}-then-{@code INSERT}, so the PRIMARY KEY unique constraint is what decides which of two
 * racing requests proceeds.
 *
 * <p>Every method is its own {@code @Transactional} boundary, deliberately: {@code
 * IdempotencyFilter} calls these outside any surrounding transaction, and {@code
 * spring.datasource.hikari.auto-commit=false} (project-wide, so a retried statement can never
 * double-post silently) means an un-annotated write here would be rolled back the moment its
 * connection returns to the pool rather than persisted.
 */
@Repository
public class IdempotencyKeyRepository {

  private final DSLContext dsl;

  public IdempotencyKeyRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Returns {@code true} if this call won the race and created the {@code IN_PROGRESS} row. */
  @Transactional
  public boolean tryClaim(String key, String fingerprint) {
    try {
      int inserted =
          dsl.insertInto(IDEMPOTENCY_KEYS)
              .set(IDEMPOTENCY_KEYS.KEY, key)
              .set(IDEMPOTENCY_KEYS.REQUEST_FINGERPRINT, fingerprint)
              .set(IDEMPOTENCY_KEYS.STATUS, "IN_PROGRESS")
              .onConflictDoNothing()
              .execute();
      return inserted == 1;
    } catch (DataAccessException e) {
      return false;
    }
  }

  @Transactional(readOnly = true)
  public Optional<IdempotencyKeysRecord> find(String key) {
    return Optional.ofNullable(
        dsl.selectFrom(IDEMPOTENCY_KEYS).where(IDEMPOTENCY_KEYS.KEY.eq(key)).fetchOne());
  }

  /**
   * Re-claims a {@code FAILED} key for retry: resets to {@code IN_PROGRESS} with the new
   * fingerprint and a cleared snapshot. Guarded by {@code WHERE status = 'FAILED'} so only one of
   * two racing retries wins; the loser (0 rows updated) must re-loop rather than assume success.
   */
  @Transactional
  public boolean reclaimFailed(String key, String fingerprint) {
    int updated =
        dsl.update(IDEMPOTENCY_KEYS)
            .set(IDEMPOTENCY_KEYS.REQUEST_FINGERPRINT, fingerprint)
            .set(IDEMPOTENCY_KEYS.STATUS, "IN_PROGRESS")
            .set(IDEMPOTENCY_KEYS.RESPONSE_SNAPSHOT, (String) null)
            .set(IDEMPOTENCY_KEYS.UPDATED_AT, DSL.currentOffsetDateTime())
            .where(IDEMPOTENCY_KEYS.KEY.eq(key))
            .and(IDEMPOTENCY_KEYS.STATUS.eq("FAILED"))
            .execute();
    return updated == 1;
  }

  @Transactional
  public void markCompleted(String key, String responseSnapshot) {
    dsl.update(IDEMPOTENCY_KEYS)
        .set(IDEMPOTENCY_KEYS.STATUS, "COMPLETED")
        .set(IDEMPOTENCY_KEYS.RESPONSE_SNAPSHOT, responseSnapshot)
        .set(IDEMPOTENCY_KEYS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(IDEMPOTENCY_KEYS.KEY.eq(key))
        .execute();
  }

  @Transactional
  public void markFailed(String key) {
    dsl.update(IDEMPOTENCY_KEYS)
        .set(IDEMPOTENCY_KEYS.STATUS, "FAILED")
        .set(IDEMPOTENCY_KEYS.UPDATED_AT, DSL.currentOffsetDateTime())
        .where(IDEMPOTENCY_KEYS.KEY.eq(key))
        .execute();
  }
}
