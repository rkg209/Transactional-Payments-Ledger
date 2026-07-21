package org.ledger.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.ledger.api.error.ErrorCode;
import org.ledger.api.error.ErrorResponseWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates {@code Authorization: ApiKey <key>} against the configured key set (ADR 0003).
 * Comparison is timing-safe and iterates the whole key set on every request, so response timing
 * does not leak set membership or size. The header value is never logged.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

  private static final String SCHEME_PREFIX = "ApiKey ";
  private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

  private final Set<String> validApiKeys;

  public ApiKeyAuthFilter(Set<String> validApiKeys) {
    this.validApiKeys = validApiKeys;
  }

  /**
   * This filter runs unconditionally on every request (it is registered directly, not scoped by
   * {@code authorizeHttpRequests}), so it must independently know which routes are public — those
   * same routes are {@code permitAll} in {@link SecurityConfig}, sourced from the same {@link
   * PublicPaths} list.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    for (String pattern : PublicPaths.PATTERNS) {
      if (PATH_MATCHER.match(pattern, path)) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(SCHEME_PREFIX)) {
      ErrorResponseWriter.write(response, ErrorCode.MISSING_CREDENTIALS);
      return;
    }

    String presented = header.substring(SCHEME_PREFIX.length());
    if (!isValidKey(presented)) {
      ErrorResponseWriter.write(response, ErrorCode.INVALID_CREDENTIALS);
      return;
    }

    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("api-key-caller", null, List.of()));
    filterChain.doFilter(request, response);
  }

  private boolean isValidKey(String presented) {
    byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
    boolean matched = false;
    // Iterate every configured key, never short-circuiting, so timing cannot reveal which key (or
    // how many) matched.
    for (String candidate : validApiKeys) {
      if (MessageDigest.isEqual(presentedBytes, candidate.getBytes(StandardCharsets.UTF_8))) {
        matched = true;
      }
    }
    return matched;
  }
}
