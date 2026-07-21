-- planning/05-api-design.md §5.5 claims the keyset sort used by GET /api/v1/accounts
-- ("ORDER BY created_at, id") is supported by the primary key index without an
-- additional sort index. That is wrong: the PK is on id alone. Without this index,
-- the cursor predicate WHERE (created_at, id) > (?, ?) would sort the whole table.
CREATE INDEX idx_accounts_created_at_id ON accounts (created_at, id);
