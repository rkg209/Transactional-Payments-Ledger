-- =============================================================================
-- V1__initial_schema.sql
-- Transactional Payments Ledger — initial schema (PostgreSQL 16)
--
-- Transcribed from planning/04-database-schema.sql, which is the authoritative
-- schema definition. Do not redesign it here.
--
-- The governing invariant, which every decision below serves:
--
--     Σ(amount_minor × direction_sign for ALL ledger_entries) = 0   at all times
--
-- Money is BIGINT minor units (cents). No floating-point type appears anywhere
-- in this schema, by design: integer arithmetic is exact, and there is no
-- rounding error to accumulate across millions of entries.
--
-- NOTE: Flyway migrations are IMMUTABLE once applied. To correct anything here,
-- add a V{n+1} migration. Never edit this file after it has run anywhere.
-- =============================================================================


-- ---------------------------------------------------------------------------
-- accounts
--
-- `balance` is a materialized CACHE of Σ(ledger_entries for this account). The
-- authoritative balance is always derivable from ledger_entries alone. If the
-- two ever disagree, the entry sum is right and this column is wrong.
-- ReconciliationService (SPEC 0005) continuously verifies they agree.
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    name         VARCHAR(255) NOT NULL,
    currency     CHAR(3)      NOT NULL DEFAULT 'USD',
    min_balance  BIGINT       NOT NULL DEFAULT 0,
    balance      BIGINT       NOT NULL DEFAULT 0,
    version      BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT accounts_pkey
        PRIMARY KEY (id),

    -- The overdraft guard, at the database level. The application checks this
    -- before writing, but this constraint is the last line of defence against a
    -- bug in that check.
    CONSTRAINT accounts_balance_gte_min
        CHECK (balance >= min_balance),

    CONSTRAINT accounts_min_balance_gte_zero
        CHECK (min_balance >= 0),

    CONSTRAINT accounts_currency_format
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT accounts_version_gte_zero
        CHECK (version >= 0)
);

COMMENT ON TABLE  accounts             IS 'Ledger accounts. Balance is a materialized cache; the authoritative balance is derived from ledger_entries.';
COMMENT ON COLUMN accounts.min_balance IS 'Minimum allowed balance in minor units. Must be >= 0. Default 0 = no overdraft.';
COMMENT ON COLUMN accounts.balance     IS 'Materialized balance in minor units. Cache of SUM(ledger_entries). Must be >= min_balance.';
COMMENT ON COLUMN accounts.version     IS 'Optimistic concurrency version counter. Incremented on every balance update.';


-- ---------------------------------------------------------------------------
-- transfers
--
-- The business-level record of one value movement. Groups a balanced pair of
-- ledger entries and gives the client a stable id to reference.
-- ---------------------------------------------------------------------------
CREATE TABLE transfers (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key  VARCHAR(255),
    from_account_id  UUID         NOT NULL,
    to_account_id    UUID         NOT NULL,
    amount_minor     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,
    status           VARCHAR(50)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT transfers_pkey
        PRIMARY KEY (id),

    CONSTRAINT transfers_from_account_fk
        FOREIGN KEY (from_account_id) REFERENCES accounts (id),

    CONSTRAINT transfers_to_account_fk
        FOREIGN KEY (to_account_id) REFERENCES accounts (id),

    CONSTRAINT transfers_status_valid
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),

    CONSTRAINT transfers_amount_positive
        CHECK (amount_minor > 0),

    CONSTRAINT transfers_currency_format
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT transfers_different_accounts
        CHECK (from_account_id <> to_account_id),

    -- Secondary guard. The primary uniqueness enforcement for idempotency is
    -- the PRIMARY KEY on idempotency_keys.key.
    CONSTRAINT transfers_idempotency_key_uq
        UNIQUE (idempotency_key)
);

COMMENT ON TABLE  transfers                  IS 'Transfer requests. One row per client request. ledger_entries is the authoritative accounting record; this table tracks status and idempotency linkage.';
COMMENT ON COLUMN transfers.idempotency_key  IS 'Client-supplied Idempotency-Key. NULL for saga sub-steps.';
COMMENT ON COLUMN transfers.status           IS 'Lifecycle: PENDING -> COMPLETED | FAILED.';


-- ---------------------------------------------------------------------------
-- ledger_entries
--
-- THE APPEND-ONLY, IMMUTABLE ACCOUNTING LOG. The source of truth for all
-- balances. Every transfer produces exactly one DEBIT and one CREDIT of equal
-- amount_minor, so the entries sum to zero.
--
-- Note the deliberate omissions: no `updated_at`, no `status`, no `reversed`
-- flag. Each of those would imply the row can be mutated. Their absence is a
-- design signal. Reversals are NEW entries with the opposite direction; the
-- original entry always stays.
--
-- amount_minor is always POSITIVE. The sign is carried by `direction`, not by
-- the sign of the amount -- a negative CREDIT is not the same thing as a DEBIT,
-- and conflating them is how ledgers become unauditable.
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_entries (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    transfer_id  UUID        NOT NULL,
    account_id   UUID        NOT NULL,
    direction    VARCHAR(6)  NOT NULL,
    amount_minor BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ledger_entries_pkey
        PRIMARY KEY (id),

    CONSTRAINT ledger_entries_transfer_fk
        FOREIGN KEY (transfer_id) REFERENCES transfers (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,

    CONSTRAINT ledger_entries_account_fk
        FOREIGN KEY (account_id) REFERENCES accounts (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,

    CONSTRAINT ledger_entries_direction_valid
        CHECK (direction IN ('DEBIT', 'CREDIT')),

    -- Zero-value entries are semantically meaningless and would mask bugs.
    CONSTRAINT ledger_entries_amount_positive
        CHECK (amount_minor > 0)
);

COMMENT ON TABLE  ledger_entries              IS 'Append-only double-entry log. IMMUTABLE: no UPDATE or DELETE permitted, enforced by trigger below.';
COMMENT ON COLUMN ledger_entries.direction    IS 'DEBIT = money leaving the account; CREDIT = money entering it.';
COMMENT ON COLUMN ledger_entries.amount_minor IS 'Absolute amount in minor units. Always > 0. Sign is encoded in direction.';
COMMENT ON COLUMN ledger_entries.created_at   IS 'Creation timestamp. The ONLY timestamp: there is no updated_at because rows are immutable.';


-- Defence-in-depth for invariant #2.
--
-- The application promises never to UPDATE or DELETE a ledger entry. But a
-- promise enforced only by discipline is not enforced at all: a stray psql
-- session, a well-meaning migration, or a future bug would silently destroy the
-- audit trail. This trigger makes the immutability true rather than merely
-- intended -- it holds even against a superuser with a direct connection.
CREATE OR REPLACE FUNCTION ledger_entries_immutability_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'ledger_entries is append-only: UPDATE and DELETE are not permitted. operation=% entry_id=%',
        TG_OP,
        COALESCE(OLD.id::TEXT, '(unknown)');
END;
$$;

COMMENT ON FUNCTION ledger_entries_immutability_guard() IS
    'Unconditionally raises on any attempt to UPDATE or DELETE a ledger_entries row.';

CREATE TRIGGER ledger_entries_immutable_tg
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION ledger_entries_immutability_guard();


-- ---------------------------------------------------------------------------
-- idempotency_keys
--
-- The PRIMARY KEY on `key` is not merely a lookup index -- it IS the
-- concurrency primitive. When two identical requests race, exactly one INSERT
-- can commit; the loser takes a unique-constraint violation and that failure is
-- the coordination signal telling it to wait for the winner's result.
--
-- This is why the check must be a single INSERT, never SELECT-then-INSERT:
-- SELECT ... FOR UPDATE on a row that does not exist yet locks NOTHING.
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    key                 VARCHAR(255) NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    response_snapshot   TEXT,
    status              VARCHAR(20)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT idempotency_keys_pkey
        PRIMARY KEY (key),

    CONSTRAINT idempotency_keys_status_valid
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),

    CONSTRAINT idempotency_keys_fingerprint_len
        CHECK (length(request_fingerprint) = 64)
);

COMMENT ON TABLE  idempotency_keys                      IS 'Idempotency gate. The PRIMARY KEY unique constraint is what serializes concurrent duplicate requests.';
COMMENT ON COLUMN idempotency_keys.request_fingerprint  IS 'SHA-256 hex digest of the raw request body. Detects key reuse with a different payload.';
COMMENT ON COLUMN idempotency_keys.response_snapshot    IS 'Serialized JSON response, stored as TEXT (not JSONB) so replay is byte-for-byte identical: JSONB would reparse and could reorder keys.';


-- ---------------------------------------------------------------------------
-- sagas
--
-- Durable saga state, persisted BEFORE each step runs so that a crash always
-- leaves a trace to recover from.
-- ---------------------------------------------------------------------------
CREATE TABLE sagas (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    type         VARCHAR(100) NOT NULL,
    state        VARCHAR(20)  NOT NULL,
    current_step INT          NOT NULL DEFAULT 0,
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT sagas_pkey
        PRIMARY KEY (id),

    CONSTRAINT sagas_state_valid
        CHECK (state IN ('STARTED', 'COMPLETED', 'COMPENSATING', 'COMPENSATED', 'FAILED')),

    CONSTRAINT sagas_current_step_gte_zero
        CHECK (current_step >= 0)
);

COMMENT ON TABLE  sagas         IS 'Saga lifecycle. State is persisted before each step so a crash can be recovered or compensated deterministically.';
COMMENT ON COLUMN sagas.state   IS 'STARTED | COMPLETED | COMPENSATING | COMPENSATED | FAILED. FAILED means compensation ITSELF failed: the system is inconsistent and needs a human.';
COMMENT ON COLUMN sagas.payload IS 'Original request inputs. Stored so recovery can reconstruct the saga after the HTTP request is long gone.';


-- ---------------------------------------------------------------------------
-- saga_steps
--
-- A step is written IN_PROGRESS in its own committed transaction BEFORE
-- forward() runs. So after a crash an IN_PROGRESS step is genuinely ambiguous --
-- forward() may or may not have committed -- and recovery must treat it as
-- POSSIBLY done. Recovery is therefore idempotent per step.
-- ---------------------------------------------------------------------------
CREATE TABLE saga_steps (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    saga_id        UUID         NOT NULL,
    step_index     INT          NOT NULL,
    step_type      VARCHAR(100) NOT NULL,
    state          VARCHAR(20)  NOT NULL,
    forward_result JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT saga_steps_pkey
        PRIMARY KEY (id),

    CONSTRAINT saga_steps_saga_fk
        FOREIGN KEY (saga_id) REFERENCES sagas (id),

    CONSTRAINT saga_steps_state_valid
        CHECK (state IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'COMPENSATED')),

    CONSTRAINT saga_steps_step_index_gte_zero
        CHECK (step_index >= 0),

    -- Duplicate step records for the same position would make recovery ambiguous.
    CONSTRAINT saga_steps_saga_step_uq
        UNIQUE (saga_id, step_index)
);

COMMENT ON TABLE  saga_steps                IS 'Per-step saga state. One row per (saga, step_index).';
COMMENT ON COLUMN saga_steps.forward_result IS 'Result of step.forward(), passed to step.compensate() during rollback so compensation has full context.';


-- ---------------------------------------------------------------------------
-- reconciliation_reports
--
-- The output of each invariant check. global_sum must ALWAYS be 0; any other
-- value means money was created or destroyed.
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_reports (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    run_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    global_sum       BIGINT      NOT NULL,
    drift_detected   BOOLEAN     NOT NULL,
    drift_details    JSONB,
    accounts_checked INT         NOT NULL,
    accounts_drifted INT         NOT NULL DEFAULT 0,

    CONSTRAINT reconciliation_reports_pkey
        PRIMARY KEY (id),

    CONSTRAINT reconciliation_reports_accounts_checked_gte_zero
        CHECK (accounts_checked >= 0),

    CONSTRAINT reconciliation_reports_accounts_drifted_gte_zero
        CHECK (accounts_drifted >= 0)
);

COMMENT ON TABLE  reconciliation_reports            IS 'Output of each ReconciliationService run.';
COMMENT ON COLUMN reconciliation_reports.global_sum IS 'Sum of signed amount_minor across ALL ledger_entries. Must be 0. Any other value means money was created or destroyed.';
