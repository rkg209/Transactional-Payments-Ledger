package org.ledger.api.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health endpoint (FR-10).
 *
 * <p>Deliberately distinct from Actuator's {@code /actuator/health}: FR-10 asks for {@code /health}
 * specifically, and database connectivity is asserted here by actually issuing a query rather than
 * by trusting an auto-configured indicator. A ledger that cannot reach its database is not
 * degraded, it is down — so this reports DOWN rather than UP-with-a-warning.
 */
@RestController
public class HealthController {

  private final DSLContext dsl;

  public HealthController(DSLContext dsl) {
    this.dsl = dsl;
  }

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    Map<String, String> body = new LinkedHashMap<>();

    boolean dbUp;
    try {
      dsl.select(DSL.one()).fetch();
      dbUp = true;
    } catch (RuntimeException e) {
      dbUp = false;
    }

    body.put("status", dbUp ? "UP" : "DOWN");
    body.put("database", dbUp ? "UP" : "DOWN");

    return dbUp
        ? ResponseEntity.ok(body)
        : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }
}
