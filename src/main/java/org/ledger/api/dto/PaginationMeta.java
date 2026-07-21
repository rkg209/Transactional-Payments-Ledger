package org.ledger.api.dto;

public record PaginationMeta(int limit, String nextCursor, boolean hasMore) {}
