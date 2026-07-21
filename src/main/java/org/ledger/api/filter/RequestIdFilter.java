package org.ledger.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Generates the {@code requestId} used to correlate a response body's {@code requestId} field, the
 * {@code X-Request-Id} response header, and server-side structured logs (§3.1).
 *
 * <p>§3.1 describes {@code SecurityFilter} as the generator, but actuator and {@code /health} both
 * bypass security, and a 401 still needs a request id — so this runs as its own filter at {@link
 * Ordered#HIGHEST_PRECEDENCE}, ahead of the security chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String MDC_KEY = "requestId";
  public static final String RESPONSE_HEADER = "X-Request-Id";

  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = "req-" + generateHex();
    // Virtual threads are pooled by the carrier thread; a leaked MDC entry would mislabel a later
    // request's logs, so this must always be cleared, including on exception propagation.
    try {
      MDC.put(MDC_KEY, requestId);
      response.setHeader(RESPONSE_HEADER, requestId);
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private static String generateHex() {
    byte[] bytes = new byte[4];
    RANDOM.nextBytes(bytes);
    StringBuilder sb = new StringBuilder(8);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}
