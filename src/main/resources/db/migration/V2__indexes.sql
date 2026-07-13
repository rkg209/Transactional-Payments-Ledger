-- =============================================================================
-- V2__indexes.sql
-- Transactional Payments Ledger — indexes
--
-- Transcribed from planning/04-database-schema.sql. That file is truncated at
-- line 618, mid-comment, and never emits indexes for sagas, saga_steps, or
-- reconciliation_reports. Those are completed here in the spirit of the ones it
-- does specify. See progress_report.md entry [001].
-- =============================================================================


-- ---------------------------------------------------------------------------
-- transfers
-- ---------------------------------------------------------------------------

-- Partial: saga sub-step transfers carry a NULL key and are excluded, keeping
-- the index small.
CREATE INDEX idx_transfers_idempotency_key
    ON transfers (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX idx_transfers_from_account_id
    ON transfers (from_account_id);

CREATE INDEX idx_transfers_to_account_id
    ON transfers (to_account_id);

-- Partial: only the states anyone ever scans for. COMPLETED is the overwhelming
-- majority of rows and nobody queries by it, so indexing it would be pure cost.
CREATE INDEX idx_transfers_status
    ON transfers (status)
    WHERE status IN ('PENDING', 'FAILED');


-- ---------------------------------------------------------------------------
-- ledger_entries
-- ---------------------------------------------------------------------------

CREATE INDEX idx_ledger_entries_account_id
    ON ledger_entries (account_id);

CREATE INDEX idx_ledger_entries_transfer_id
    ON ledger_entries (transfer_id);

-- The covering index for the reconciliation aggregate:
--
--   SELECT account_id,
--          SUM(CASE WHEN direction='DEBIT'  THEN -amount_minor
--                   WHEN direction='CREDIT' THEN  amount_minor END)
--   FROM ledger_entries GROUP BY account_id;
--
-- All three projected columns live in the index, so PostgreSQL can satisfy the
-- SUM with an index-only scan and never touch the heap. On a ledger_entries
-- table with millions of rows this is the difference between a background job
-- that runs every minute unnoticed and one that hammers the database.
CREATE INDEX idx_ledger_entries_reconciliation
    ON ledger_entries (account_id, direction, amount_minor);

CREATE INDEX idx_ledger_entries_created_at
    ON ledger_entries (created_at DESC);


-- ---------------------------------------------------------------------------
-- idempotency_keys
--
-- The PRIMARY KEY on `key` already serves the hot lookup path. This partial
-- index serves the polling loop's status check and future cleanup jobs.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_idempotency_keys_status
    ON idempotency_keys (status, updated_at)
    WHERE status IN ('IN_PROGRESS', 'FAILED');


-- ---------------------------------------------------------------------------
-- sagas
--
-- Not present in the (truncated) source file; added here.
--
-- This partial index serves exactly one query, and it is an important one:
-- SagaRecoveryRunner's startup scan for sagas that were mid-flight when the
-- process died. Restricting it to the non-terminal states keeps it tiny --
-- COMPLETED and COMPENSATED sagas accumulate forever and are never scanned for.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_sagas_recovery
    ON sagas (state)
    WHERE state NOT IN ('COMPLETED', 'COMPENSATED');


-- ---------------------------------------------------------------------------
-- saga_steps
--
-- Not present in the (truncated) source file; added here.
--
-- The UNIQUE (saga_id, step_index) constraint already provides an index that
-- covers lookup-by-saga, so no additional index is needed for the recovery path.
-- This one supports finding the steps still needing compensation.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_saga_steps_state
    ON saga_steps (saga_id, state);


-- ---------------------------------------------------------------------------
-- reconciliation_reports
--
-- Not present in the (truncated) source file; added here.
-- Supports "give me the latest report", which is the only read this table has.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_reconciliation_reports_run_at
    ON reconciliation_reports (run_at DESC);
