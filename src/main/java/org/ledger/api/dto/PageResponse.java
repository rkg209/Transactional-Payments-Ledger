package org.ledger.api.dto;

import java.util.List;

public record PageResponse<T>(List<T> data, PaginationMeta pagination) {}
