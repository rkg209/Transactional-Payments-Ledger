package org.ledger.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String errorCode,
    String message,
    String requestId,
    OffsetDateTime timestamp,
    Map<String, Object> details) {}
