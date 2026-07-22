package org.ledger.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ledger.api.filter.RequestIdFilter;
import org.ledger.support.AbstractApiIT;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * SPEC 0009 (NFR-19): with the {@code json} profile active, every log line is a JSON object
 * carrying {@code requestId} (the same value {@link RequestIdFilter} put in MDC and returned as
 * {@code X-Request-Id}) as a top-level field, not buried in {@code message}.
 *
 * <p>Captures events via a {@link ListAppender} attached to the root logger, then re-encodes each
 * one through the actual {@code JSON_CONSOLE} appender's encoder from {@code logback-spring.xml} --
 * this exercises the real configured wiring, not a duplicate encoder built just for the test.
 *
 * <p>Logback's {@code LoggerContext} is a single JVM-wide singleton shared by every Spring context
 * booted in this same Maven Surefire/Failsafe fork -- whichever test class most recently triggered
 * a cold {@code SpringApplication} boot last decides its live configuration, regardless of which
 * profile any individual (possibly context-cache-reused) test actually wants. {@link
 * #forceJsonLoggingReconfiguration} reinitializes it explicitly from this test's own {@code
 * Environment} (which genuinely has {@code json} active, a Spring-level fact untouched by that
 * race) so this test's outcome does not depend on suite ordering.
 */
@ActiveProfiles("json")
class LoggingIT extends AbstractApiIT {

  @Autowired private ConfigurableEnvironment environment;

  private Logger rootLogger;
  private ListAppender<ILoggingEvent> capture;

  @BeforeEach
  void forceJsonLoggingReconfiguration() {
    LoggingSystem loggingSystem = LoggingSystem.get(getClass().getClassLoader());
    loggingSystem.cleanUp();
    loggingSystem.beforeInitialize();
    loggingSystem.initialize(
        new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);

    rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    capture = new ListAppender<>();
    capture.start();
    rootLogger.addAppender(capture);
  }

  @AfterEach
  void detachCaptureAppender() {
    rootLogger.detachAppender(capture);
  }

  @Test
  @SuppressWarnings("unchecked")
  void everyLineParsesAsJsonAndCarriesTheRequestId() throws Exception {
    Appender<ILoggingEvent> jsonAppender = rootLogger.getAppender("JSON_CONSOLE");
    assertThat(jsonAppender)
        .as("logback-spring.xml's json profile must register JSON_CONSOLE on root")
        .isNotNull();
    Encoder<ILoggingEvent> encoder =
        ((OutputStreamAppender<ILoggingEvent>) jsonAppender).getEncoder();

    UUID fromId = createAccount();
    UUID toId = createAccount();
    seedInitialBalance(fromId, 10_000L);

    Map<String, Object> transferRequest =
        Map.of(
            "fromAccountId",
            fromId.toString(),
            "toAccountId",
            toId.toString(),
            "amount",
            100,
            "currency",
            "USD");
    // TransferService.execute logs "transfer_applied" synchronously on this same request thread,
    // so RequestIdFilter's MDC entry is still set when it fires -- unlike account creation, which
    // has no INFO-level log site of its own to carry it.
    ResponseEntity<Map> transferResponse =
        rest.exchange(
            url("/api/v1/transfers"),
            HttpMethod.POST,
            authedWithIdempotencyKey(transferRequest, UUID.randomUUID().toString()),
            Map.class);
    String requestId = transferResponse.getHeaders().getFirst(RequestIdFilter.RESPONSE_HEADER);
    assertThat(requestId).isNotBlank();

    ObjectMapper mapper = new ObjectMapper();
    assertThat(capture.list).isNotEmpty();

    boolean sawRequestScopedLine = false;
    for (ILoggingEvent event : capture.list) {
      String line = new String(encoder.encode(event), StandardCharsets.UTF_8).trim();
      JsonNode json = mapper.readTree(line);
      assertThat(json.has("timestamp")).isTrue();
      assertThat(json.has("level")).isTrue();
      assertThat(json.has("logger")).isTrue();
      assertThat(json.has("thread")).isTrue();
      assertThat(json.has("message")).isTrue();
      if (json.has("requestId") && requestId.equals(json.get("requestId").asText())) {
        sawRequestScopedLine = true;
      }
    }
    assertThat(sawRequestScopedLine)
        .as("at least one captured line during the request must carry this request's requestId")
        .isTrue();
  }

  private UUID createAccount() {
    Map<String, Object> request =
        Map.of("name", "logging-it-" + UUID.randomUUID(), "currency", "USD");
    ResponseEntity<Map> response =
        rest.exchange(
            url("/api/v1/accounts"),
            HttpMethod.POST,
            authedWithIdempotencyKey(request, UUID.randomUUID().toString()),
            Map.class);
    return UUID.fromString((String) response.getBody().get("id"));
  }
}
