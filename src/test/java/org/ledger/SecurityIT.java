package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractApiIT;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** SPEC 0002 — every /api/v1/* path requires a valid API key; /health and /actuator do not. */
class SecurityIT extends AbstractApiIT {

  @Test
  void missingAuthorizationHeaderIs401MissingCredentials() {
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.GET,
            new HttpEntity<>(null, new HttpHeaders()),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().get("errorCode")).isEqualTo("MISSING_CREDENTIALS");
  }

  @Test
  void wrongApiKeyIs401InvalidCredentials() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "ApiKey wrong-key");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"), HttpMethod.GET, new HttpEntity<>(null, headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void wrongSchemeIs401MissingCredentials() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + TEST_API_KEY);
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"), HttpMethod.GET, new HttpEntity<>(null, headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().get("errorCode")).isEqualTo("MISSING_CREDENTIALS");
  }

  @Test
  void validKeySucceedsOnAccountsList() {
    ResponseEntity<Map> response =
        rest.exchange(url("/api/v1/accounts"), HttpMethod.GET, authed(null), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void validKeySucceedsOnAccountCreate() {
    Map<String, Object> request = Map.of("name", "carol", "currency", "USD");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, "sec-it-key"),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  @Test
  void healthIsReachableWithoutApiKey() {
    ResponseEntity<Map> response =
        rest.exchange(
            url("/health"), HttpMethod.GET, new HttpEntity<>(null, new HttpHeaders()), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void actuatorHealthIsReachableWithoutApiKey() {
    ResponseEntity<Map> response =
        rest.exchange(
            url("/actuator/health"),
            HttpMethod.GET,
            new HttpEntity<>(null, new HttpHeaders()),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
