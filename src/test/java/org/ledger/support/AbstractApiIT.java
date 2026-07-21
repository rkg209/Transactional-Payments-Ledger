package org.ledger.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for HTTP-level integration tests (SPEC 0002). Registers a fixed test API key via
 * {@code ledger.api-keys} so tests never depend on the dev-default key in {@code application.yml}.
 */
public abstract class AbstractApiIT extends AbstractPostgresIT {

  public static final String TEST_API_KEY = "test-api-key-0002";

  @Autowired protected TestRestTemplate rest;

  @LocalServerPort protected int port;

  @DynamicPropertySource
  static void apiKeyProperty(DynamicPropertyRegistry registry) {
    registry.add("ledger.api-keys", () -> TEST_API_KEY);
  }

  protected String url(String path) {
    return "http://localhost:" + port + path;
  }

  protected <T> HttpEntity<T> authed(T body) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + TEST_API_KEY);
    return new HttpEntity<>(body, headers);
  }

  protected <T> HttpEntity<T> authedWithIdempotencyKey(T body, String idempotencyKey) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + TEST_API_KEY);
    headers.set("Idempotency-Key", idempotencyKey);
    return new HttpEntity<>(body, headers);
  }
}
