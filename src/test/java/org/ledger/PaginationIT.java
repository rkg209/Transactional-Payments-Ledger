package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.support.AbstractApiIT;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 0002 — the headline pagination test: cursor stability under a concurrent insert, which is
 * the entire reason ADR 0004 chose cursor pagination over offset (§5.1).
 */
class PaginationIT extends AbstractApiIT {

  @Test
  void cursorIsStableWhenARowIsInsertedInsidePage1sRange() throws InterruptedException {
    List<UUID> created = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      created.add(createAccount("acct-" + i));
      Thread.sleep(5); // ensure distinct created_at ordering
    }

    ResponseEntity<Map> page1Response =
        rest.exchange(url("/api/v1/accounts?limit=3"), HttpMethod.GET, authed(null), Map.class);
    assertThat(page1Response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page1 = page1Response.getBody();
    List<Map<String, Object>> page1Data = (List<Map<String, Object>>) page1.get("data");
    Map<String, Object> page1Pagination = (Map<String, Object>) page1.get("pagination");
    assertThat(page1Data).hasSize(3);
    assertThat(page1Pagination.get("hasMore")).isEqualTo(true);
    String cursor = (String) page1Pagination.get("nextCursor");
    assertThat(cursor).isNotNull();

    // Insert an account whose created_at falls strictly between account 0 and account 1 -- inside
    // page 1's already-served range.
    OffsetDateTime insidePage1Range = fetchCreatedAt(created.get(0)).plusNanos(500_000);
    UUID insertedInsidePage1 = UUID.randomUUID();
    tx.executeWithoutResult(
        status ->
            dsl.execute(
                "INSERT INTO accounts (id, name, currency, created_at, updated_at) VALUES (?, ?, ?,"
                    + " ?::timestamptz, now())",
                insertedInsidePage1,
                "late-arrival",
                "USD",
                insidePage1Range.toString()));

    ResponseEntity<Map> page2Response =
        rest.exchange(
            url("/api/v1/accounts?limit=3&cursor=" + cursor),
            HttpMethod.GET,
            authed(null),
            Map.class);
    assertThat(page2Response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> page2 = page2Response.getBody();
    List<Map<String, Object>> page2Data = (List<Map<String, Object>>) page2.get("data");
    Map<String, Object> page2Pagination = (Map<String, Object>) page2.get("pagination");

    List<String> page2Ids = page2Data.stream().map(a -> (String) a.get("id")).toList();
    // The row inserted inside page 1's already-served range must not resurface on page 2, and the
    // two accounts that were always after the cursor must appear exactly once, in order.
    assertThat(page2Ids).containsExactly(created.get(3).toString(), created.get(4).toString());
    assertThat(page2Ids).doesNotContain(insertedInsidePage1.toString());
    assertThat(page2Pagination.get("hasMore")).isEqualTo(false);
    assertThat(page2Pagination.get("nextCursor")).isNull();

    List<String> combined =
        new ArrayList<>(page1Data.stream().map(a -> (String) a.get("id")).toList());
    combined.addAll(page2Ids);
    assertThat(combined).doesNotHaveDuplicates();
  }

  @Test
  void limitAbove100Is400() {
    ResponseEntity<Map> response =
        rest.exchange(url("/api/v1/accounts?limit=101"), HttpMethod.GET, authed(null), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void emptyResultHasNoMoreAndNullCursor() {
    ResponseEntity<Map> response =
        rest.exchange(url("/api/v1/accounts?limit=20"), HttpMethod.GET, authed(null), Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> pagination = (Map<String, Object>) response.getBody().get("pagination");
    assertThat(pagination.get("hasMore")).isEqualTo(false);
    assertThat(pagination.get("nextCursor")).isNull();
  }

  @Test
  void lastPageIsNonEmptyWithHasMoreFalse() {
    createAccount("solo");
    ResponseEntity<Map> response =
        rest.exchange(url("/api/v1/accounts?limit=20"), HttpMethod.GET, authed(null), Map.class);
    Map<String, Object> body = response.getBody();
    List<?> data = (List<?>) body.get("data");
    Map<String, Object> pagination = (Map<String, Object>) body.get("pagination");
    assertThat(data).isNotEmpty();
    assertThat(pagination.get("hasMore")).isEqualTo(false);
  }

  @Test
  void malformedCursorIs400() {
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts?cursor=not-a-valid-cursor"),
            HttpMethod.GET,
            authed(null),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().get("errorCode")).isEqualTo("VALIDATION_ERROR");
  }

  private UUID createAccount(String name) {
    Map<String, Object> request = Map.of("name", name, "currency", "USD");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, UUID.randomUUID().toString()),
            Map.class);
    return UUID.fromString((String) response.getBody().get("id"));
  }

  private OffsetDateTime fetchCreatedAt(UUID accountId) {
    return dsl.select(org.jooq.impl.DSL.field("created_at", OffsetDateTime.class))
        .from(org.jooq.impl.DSL.table("accounts"))
        .where(org.jooq.impl.DSL.field("id", UUID.class).eq(accountId))
        .fetchOne(0, OffsetDateTime.class);
  }
}
