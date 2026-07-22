package org.ledger.infrastructure;

import org.apache.catalina.connector.Connector;
import org.springframework.stereotype.Component;

/**
 * Holds the paused Tomcat {@link Connector} between {@link TomcatConnectorPauseCustomizer} (which
 * pauses it at server-creation time) and {@code SagaRecoveryRunner} (which resumes it once saga
 * recovery has run). A separate bean, not a field on either of those two, because the customizer
 * runs during {@code refresh()} and the runner runs after it -- both need a stable place to hand
 * the connector reference across that boundary.
 */
@Component
public class TomcatConnectorGate {

  private volatile Connector connector;

  void capture(Connector connector) {
    this.connector = connector;
  }

  public void resume() {
    Connector c = connector;
    if (c != null) {
      c.resume();
    }
  }
}
