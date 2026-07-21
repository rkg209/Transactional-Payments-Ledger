package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractApiIT;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** SPEC 0002 — full HTTP round trip for a transfer, asserting the total is conserved. */
class TransferApiIT extends AbstractApiIT {

  @Test
  void transferMovesBalancesImmediatelyAndConservesTheTotal() {
    UUID aliceId = createAccount("alice", "USD");
    UUID bobId = createAccount("bob", "USD");
    seedInitialBalance(aliceId, 10_000L);

    Map<String, Object> transferRequest =
        Map.of(
            "fromAccountId",
            aliceId.toString(),
            "toAccountId",
            bobId.toString(),
            "amount",
            2_500,
            "currency",
            "USD");

    ResponseEntity<Map> transferResponse =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, UUID.randomUUID().toString()),
            Map.class);

    assertThat(transferResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(transferResponse.getHeaders().getLocation()).isNotNull();
    String transferId = (String) transferResponse.getBody().get("id");
    assertThat(transferResponse.getBody().get("status")).isEqualTo("COMPLETED");

    ResponseEntity<Map> aliceBalance =
        rest.exchange(
            url("/api/v1/accounts/" + aliceId + "/balance"),
            HttpMethod.GET,
            authed(null),
            Map.class);
    ResponseEntity<Map> bobBalance =
        rest.exchange(
            url("/api/v1/accounts/" + bobId + "/balance"), HttpMethod.GET, authed(null), Map.class);

    assertThat(((Number) aliceBalance.getBody().get("balance")).longValue()).isEqualTo(7_500L);
    assertThat(((Number) bobBalance.getBody().get("balance")).longValue()).isEqualTo(2_500L);

    long total =
        ((Number) aliceBalance.getBody().get("balance")).longValue()
            + ((Number) bobBalance.getBody().get("balance")).longValue();
    assertThat(total).isEqualTo(10_000L);

    ResponseEntity<Map> getTransfer =
        rest.exchange(
            url("/api/v1/transfers/" + transferId), HttpMethod.GET, authed(null), Map.class);
    assertThat(getTransfer.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getTransfer.getBody().get("status")).isEqualTo("COMPLETED");
    assertThat(getTransfer.getBody().get("fromAccountId")).isEqualTo(aliceId.toString());
    assertThat(getTransfer.getBody().get("toAccountId")).isEqualTo(bobId.toString());
  }

  private UUID createAccount(String name, String currency) {
    Map<String, Object> request = Map.of("name", name, "currency", currency);
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, UUID.randomUUID().toString()),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return UUID.fromString((String) response.getBody().get("id"));
  }
}
