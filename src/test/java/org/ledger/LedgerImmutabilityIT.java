package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountResult;
import org.ledger.account.AccountService;
import org.ledger.support.AbstractPostgresIT;
import org.ledger.transfer.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Invariant #2, enforced by the database itself: {@code ledger_entries} rejects UPDATE and DELETE.
 * Migrated out of {@code WalkingSkeletonIT} (SPEC 0000) now that real services exist to seed data
 * with, instead of raw INSERT SQL.
 */
class LedgerImmutabilityIT extends AbstractPostgresIT {

  @Autowired private AccountService accountService;
  @Autowired private TransferService transferService;

  @Test
  void ledgerEntriesRejectUpdateAndDelete() {
    AccountResult from = accountService.createAccount("immutability-src", "USD", 0);
    AccountResult to = accountService.createAccount("immutability-dst", "USD", 0);
    seedInitialBalance(from.id(), 1_000L);

    UUID transferId =
        transferService
            .execute(from.id(), to.id(), 100L, "USD", "ledger-immutability-it-key")
            .transferId();

    UUID entryId =
        tx.execute(
            status ->
                dsl.select(DSL.field("id", UUID.class))
                    .from(DSL.table("ledger_entries"))
                    .where(DSL.field("transfer_id", UUID.class).eq(transferId))
                    .and(DSL.field("direction", String.class).eq("DEBIT"))
                    .fetchOne(0, UUID.class));

    // Each attempt gets its own transaction: the trigger's exception aborts the transaction it
    // fires in, so they cannot share one.
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        dsl.execute(
                            "UPDATE ledger_entries SET amount_minor = 999 WHERE id = ?", entryId)))
        .hasMessageContaining("append-only");

    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> dsl.execute("DELETE FROM ledger_entries WHERE id = ?", entryId)))
        .hasMessageContaining("append-only");

    Long amount =
        tx.execute(
            status ->
                dsl.select(DSL.field("amount_minor", Long.class))
                    .from(DSL.table("ledger_entries"))
                    .where(DSL.field("id", UUID.class).eq(entryId))
                    .fetchOne(0, Long.class));

    assertThat(amount).isEqualTo(100L);
  }
}
