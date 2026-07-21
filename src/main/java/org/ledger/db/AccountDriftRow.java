package org.ledger.db;

import java.util.UUID;

/** One account whose {@code balance} disagrees with the signed sum of its ledger entries. */
public record AccountDriftRow(UUID accountId, long materializedBalance, long entrySum) {}
