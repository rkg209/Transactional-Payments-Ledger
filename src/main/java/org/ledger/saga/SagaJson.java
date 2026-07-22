package org.ledger.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.jooq.JSONB;

/**
 * The one place this module touches Jackson directly, following the {@code static final
 * ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()} convention already used by
 * {@code ReconciliationService}, {@code Cursor}, and {@code ErrorResponseWriter}.
 */
final class SagaJson {

  static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private SagaJson() {}

  static JSONB toJsonb(Object value) {
    try {
      return JSONB.valueOf(MAPPER.writeValueAsString(value));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize saga JSON", e);
    }
  }

  static <T> T fromJsonb(JSONB jsonb, Class<T> type) {
    try {
      return MAPPER.readValue(jsonb.data(), type);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to deserialize saga JSON", e);
    }
  }

  static JsonNode toJsonNode(JSONB jsonb) {
    if (jsonb == null) {
      return null;
    }
    try {
      return MAPPER.readTree(jsonb.data());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to parse saga JSON", e);
    }
  }
}
