package org.ledger.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Pauses the Tomcat connector the instant it is created, before it ever accepts a connection.
 *
 * <p>Spring Boot's {@code ApplicationRunner}s (where {@code SagaRecoveryRunner} lives) run
 * <b>after</b> the embedded connector has already started accepting traffic -- the web server
 * starts inside {@code refresh()}, runners fire afterward. That ordering would let a request land
 * before saga recovery has reconciled crashed-mid-step sagas. Pausing here and resuming in {@code
 * SagaRecoveryRunner} (via {@link TomcatConnectorGate}) closes that window: "recovery runs before
 * the HTTP server accepts traffic" becomes true by construction, not by luck of scheduling.
 */
@Component
public class TomcatConnectorPauseCustomizer
    implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

  private static final Logger log = LoggerFactory.getLogger(TomcatConnectorPauseCustomizer.class);

  private final TomcatConnectorGate gate;

  public TomcatConnectorPauseCustomizer(TomcatConnectorGate gate) {
    this.gate = gate;
  }

  @Override
  public void customize(TomcatServletWebServerFactory factory) {
    factory.addConnectorCustomizers(
        connector -> {
          connector.pause();
          gate.capture(connector);
          log.info("Tomcat connector paused pending saga recovery");
        });
  }
}
