package org.ledger.api.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import org.ledger.api.dto.ErrorResponse;
import org.ledger.api.filter.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

/**
 * Renders the standard {@link ErrorResponse} JSON envelope from code that runs outside the
 * DispatcherServlet — filters — where {@code @RestControllerAdvice} cannot reach. {@code
 * ApiKeyAuthFilter} (a 401) and {@code IdempotencyFilter} (422/503) both write through here so
 * every error response, regardless of which layer raises it, uses the same shape (§3.1).
 */
public final class ErrorResponseWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private ErrorResponseWriter() {}

  public static void write(HttpServletResponse response, ErrorCode code) throws IOException {
    write(response, code, null);
  }

  public static void write(
      HttpServletResponse response, ErrorCode code, Map<String, Object> details)
      throws IOException {
    ErrorResponse body =
        new ErrorResponse(
            code.name(),
            code.defaultMessage(),
            MDC.get(RequestIdFilter.MDC_KEY),
            OffsetDateTime.now(),
            details);
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(MAPPER.writeValueAsString(body));
  }
}
