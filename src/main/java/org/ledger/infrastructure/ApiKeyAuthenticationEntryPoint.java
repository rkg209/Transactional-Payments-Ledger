package org.ledger.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.ledger.api.error.ErrorCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Renders any 401 that reaches Spring Security's own entry point (i.e. did not already terminate
 * inside {@link ApiKeyAuthFilter}) as the standard {@code ErrorResponse} JSON, rather than Spring
 * Security's default empty body.
 */
@Component
public class ApiKeyAuthenticationEntryPoint implements AuthenticationEntryPoint {

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    SecurityErrorResponseWriter.write(response, ErrorCode.MISSING_CREDENTIALS);
  }
}
