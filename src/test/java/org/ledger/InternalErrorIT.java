package org.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ledger.account.AccountService;
import org.ledger.support.AbstractApiIT;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC 0002 — {@code 500 INTERNAL_ERROR}: an unhandled exception is reported without leaking a
 * stack trace. Isolated from {@link ErrorModelIT} because {@code @MockBean} replaces {@link
 * AccountService} for this test class's entire Spring context, so no test in this class can create
 * a real account.
 */
class InternalErrorIT extends AbstractApiIT {

  @MockBean private AccountService accountService;

  @Test
  void internalErrorNeverLeaksAStackTrace() {
    doThrow(new RuntimeException("boom")).when(accountService).getAccount(any());

    UUID any = UUID.randomUUID();
    ResponseEntity<Map> response =
        rest.exchange(url("/api/v1/accounts/" + any), HttpMethod.GET, authed(null), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().get("errorCode")).isEqualTo("INTERNAL_ERROR");
    assertThat(response.getBody().toString())
        .doesNotContain("boom")
        .doesNotContain("RuntimeException");
  }
}
