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

/**
 * SPEC 0002 — asserts the served OpenAPI document matches the implemented surface (ADR 0004),
 * rather than merely claiming it does.
 */
class OpenApiIT extends AbstractApiIT {

  @Test
  void openApiDocumentListsAllSixEndpointsAndTheApiKeyScheme() {
    ResponseEntity<Map> response =
        rest.exchange(
            url("/v3/api-docs"),
            HttpMethod.GET,
            new HttpEntity<>(null, new HttpHeaders()),
            Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = response.getBody();
    Map<String, Object> paths = (Map<String, Object>) body.get("paths");

    // /health is also documented (it is a real, reachable endpoint), so this asserts the five
    // /api/v1 path keys are present -- not that they are the document's only keys.
    assertThat(paths)
        .containsKeys(
            "/api/v1/accounts",
            "/api/v1/accounts/{accountId}",
            "/api/v1/accounts/{accountId}/balance",
            "/api/v1/transfers",
            "/api/v1/transfers/{transferId}");
    assertThat(paths.keySet().stream().filter(p -> p.startsWith("/api/v1"))).hasSize(5);

    Map<String, Object> accountsPath = (Map<String, Object>) paths.get("/api/v1/accounts");
    assertThat(accountsPath).containsKeys("get", "post");

    Map<String, Object> components = (Map<String, Object>) body.get("components");
    Map<String, Object> securitySchemes = (Map<String, Object>) components.get("securitySchemes");
    assertThat(securitySchemes).containsKey("ApiKeyAuth");
  }

  @Test
  void swaggerUiIsReachableWithoutApiKey() {
    ResponseEntity<String> response =
        rest.exchange(
            url("/swagger-ui.html"),
            HttpMethod.GET,
            new HttpEntity<>(null, new HttpHeaders()),
            String.class);
    assertThat(
            response.getStatusCode().is3xxRedirection()
                || response.getStatusCode().is2xxSuccessful())
        .isTrue();
  }
}
