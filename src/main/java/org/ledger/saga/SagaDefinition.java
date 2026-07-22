package org.ledger.saga;

import java.util.List;
import org.jooq.JSONB;

/**
 * The saga's input parameters, serialized verbatim into {@code sagas.payload}. Recovery
 * reconstructs this from the DB after a restart -- the in-memory request is gone by then -- so its
 * shape is a durability contract, not just a convenience DTO.
 */
public record SagaDefinition(String type, String currency, String description, List<SagaLeg> legs) {

  public static final String TYPE_MULTI_LEG_TRANSFER = "MULTI_LEG_TRANSFER";

  JSONB toJsonb() {
    return SagaJson.toJsonb(this);
  }

  static SagaDefinition fromJsonb(JSONB payload) {
    return SagaJson.fromJsonb(payload, SagaDefinition.class);
  }
}
