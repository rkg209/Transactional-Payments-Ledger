package org.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.ledger.idempotency.IdempotencyFilter;
import org.ledger.support.AbstractApiIT;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ReconciliationApiIT extends AbstractApiIT {

  @Test
  void getReportBeforeAnyRunComputesAFreshCleanReport() {
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/reconciliation/report"), HttpMethod.GET, authed(null), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().get("driftDetected")).isEqualTo(false);
    assertThat(((Number) response.getBody().get("globalSum")).longValue()).isZero();
  }

  @Test
  void postRunRequiresIdempotencyKeyAndReplaysOnRepeat() {
    ResponseEntity<Map> missingKey =
        rest.exchange(url("/api/v1/reconciliation/run"), HttpMethod.POST, authed(null), Map.class);
    assertThat(missingKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(missingKey.getBody().get("errorCode")).isEqualTo("MISSING_IDEMPOTENCY_KEY");

    String key = UUID.randomUUID().toString();
    ResponseEntity<Map> first =
        rest.exchange(
            url("/api/v1/reconciliation/run"),
            HttpMethod.POST,
            authedWithIdempotencyKey(null, key),
            Map.class);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    String reportId = (String) first.getBody().get("id");

    int reportRowsAfterFirst = dsl.fetchCount(DSL.table("reconciliation_reports"));

    ResponseEntity<Map> replay =
        rest.exchange(
            url("/api/v1/reconciliation/run"),
            HttpMethod.POST,
            authedWithIdempotencyKey(null, key),
            Map.class);
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getHeaders().getFirst(IdempotencyFilter.REPLAYED_HEADER)).isEqualTo("true");
    assertThat(replay.getBody().get("id")).isEqualTo(reportId);
    assertThat(dsl.fetchCount(DSL.table("reconciliation_reports"))).isEqualTo(reportRowsAfterFirst);

    ResponseEntity<Map> report =
        rest.exchange(
            url("/api/v1/reconciliation/report"), HttpMethod.GET, authed(null), Map.class);
    assertThat(report.getBody().get("id")).isEqualTo(reportId);
  }

  /**
   * GET only, deliberately, matching {@code SecurityIT}'s convention: {@code ApiKeyAuthFilter} runs
   * ahead of every {@code /api/v1/**} method identically regardless of verb, so GET is sufficient
   * evidence here too. An unauthenticated POST triggers a JDK {@code HttpURLConnection} quirk
   * ("cannot retry due to server authentication, in streaming mode") once the server challenges a
   * request whose body has already started streaming — a client-library limitation, not a server
   * behavior worth a second, POST-shaped copy of the same assertion.
   */
  @Test
  void getReportRequiresAnApiKey() {
    ResponseEntity<Map> getResponse =
        rest.exchange(
            url("/api/v1/reconciliation/report"),
            HttpMethod.GET,
            new HttpEntity<>(null, new HttpHeaders()),
            Map.class);
    assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
