package org.ledger.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.ledger.api.dto.ErrorResponse;
import org.ledger.api.error.ErrorCode;
import org.ledger.api.filter.RequestIdFilter;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

/**
 * Renders a 401 as the standard {@link ErrorResponse} JSON body. Spring Security's default behavior
 * on authentication failure is an empty body, which would break the §3.1 contract that every error
 * response — including auth failures — uses the same envelope.
 */
final class SecurityErrorResponseWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  private SecurityErrorResponseWriter() {}

  static void write(HttpServletResponse response, ErrorCode code) throws IOException {
    ErrorResponse body =
        new ErrorResponse(
            code.name(),
            code.defaultMessage(),
            MDC.get(RequestIdFilter.MDC_KEY),
            OffsetDateTime.now(),
            null);
    response.setStatus(code.status().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(MAPPER.writeValueAsString(body));
  }
}
