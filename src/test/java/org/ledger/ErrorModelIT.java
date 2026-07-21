package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractApiIT;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 0002 — one test per §3.2 catalogue row reachable end to end from this spec. {@code
 * INTERNAL_ERROR} lives in {@link InternalErrorIT} (it needs a mocked service bean, which cannot
 * share a Spring context with the real-database tests here). Codes triggered only by later specs
 * (IDEMPOTENCY_KEY_REUSE, IDEMPOTENCY_TIMEOUT, CONFLICT_RETRY_EXHAUSTED, SAGA_COMPENSATED,
 * SAGA_NOT_FOUND) are covered by {@link GlobalExceptionHandlerUnitTest} instead.
 */
class ErrorModelIT extends AbstractApiIT {

  @Test
  void validationErrorOnBlankAccountName() {
    Map<String, Object> request = Map.of("name", "", "currency", "USD");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, "e1"),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().get("errorCode")).isEqualTo("VALIDATION_ERROR");
    assertThat((List<?>) ((Map) response.getBody().get("details")).get("violations")).isNotEmpty();
  }

  @Test
  void missingIdempotencyKeyOnPost() {
    Map<String, Object> request = Map.of("name", "dave", "currency", "USD");
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + TEST_API_KEY);
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().get("errorCode")).isEqualTo("MISSING_IDEMPOTENCY_KEY");
  }

  @Test
  void malformedJsonBody() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + TEST_API_KEY);
    headers.set("Idempotency-Key", "e3");
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            new HttpEntity<>("{not-json", headers),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().get("errorCode")).isEqualTo("MALFORMED_REQUEST");
  }

  @Test
  void missingCredentials() {
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
  void invalidCredentials() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "ApiKey nope");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"), HttpMethod.GET, new HttpEntity<>(null, headers), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void accountNotFound() {
    UUID missing = UUID.randomUUID();
    ResponseEntity<Map> response =
        rest.exchange(url("/api/v1/accounts/" + missing), HttpMethod.GET, authed(null), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().get("errorCode")).isEqualTo("ACCOUNT_NOT_FOUND");
    assertThat(((Map) response.getBody().get("details")).get("accountId"))
        .isEqualTo(missing.toString());
  }

  @Test
  void transferNotFound() {
    UUID missing = UUID.randomUUID();
    ResponseEntity<Map> response =
        rest.exchange(url("/api/v1/transfers/" + missing), HttpMethod.GET, authed(null), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody().get("errorCode")).isEqualTo("TRANSFER_NOT_FOUND");
  }

  @Test
  void insufficientFundsReportsSpendableBalanceNotRawBalance() {
    UUID aliceId = createAccount("alice-if", "USD");
    UUID bobId = createAccount("bob-if", "USD");
    seedAccountState(aliceId, 1_000L, 200L); // spendable = 1000 - 200 = 800

    Map<String, Object> transferRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            bobId.toString(),
            "amount",
            900,
            "currency",
            "USD");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, "e-if"),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody().get("errorCode")).isEqualTo("INSUFFICIENT_FUNDS");
    Map<?, ?> details = (Map<?, ?>) response.getBody().get("details");
    assertThat(((Number) details.get("availableBalance")).longValue()).isEqualTo(800L);
  }

  @Test
  void currencyMismatch() {
    UUID aliceId = createAccount("alice-cm", "USD");
    UUID bobId = createAccount("bob-cm", "EUR");
    seedInitialBalance(aliceId, 10_000L);

    Map<String, Object> transferRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            bobId.toString(),
            "amount",
            100,
            "currency",
            "USD");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, "e-cm"),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody().get("errorCode")).isEqualTo("CURRENCY_MISMATCH");
  }

  @Test
  void sameAccountTransfer() {
    UUID aliceId = createAccount("alice-sa", "USD");
    seedInitialBalance(aliceId, 10_000L);

    Map<String, Object> transferRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            aliceId.toString(),
            "amount",
            100,
            "currency",
            "USD");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, "e-sa"),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody().get("errorCode")).isEqualTo("SAME_ACCOUNT_TRANSFER");
  }

  @Test
  void invalidAmountZero() {
    UUID aliceId = createAccount("alice-ia", "USD");
    UUID bobId = createAccount("bob-ia", "USD");
    seedInitialBalance(aliceId, 10_000L);

    Map<String, Object> transferRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            bobId.toString(),
            "amount",
            0,
            "currency",
            "USD");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, "e-ia"),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody().get("errorCode")).isEqualTo("INVALID_AMOUNT");
  }

  private UUID createAccount(String name, String currency) {
    Map<String, Object> request = Map.of("name", name, "currency", currency);
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, UUID.randomUUID().toString()),
            Map.class);
    return UUID.fromString((String) response.getBody().get("id"));
  }
}
