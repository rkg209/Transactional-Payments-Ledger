package org.ledger.infrastructure;

/**
 * The single list of unauthenticated routes, shared by {@link SecurityConfig}'s {@code permitAll}
 * matchers and {@link ApiKeyAuthFilter}'s {@code shouldNotFilter}. One list, not two, so the two
 * can never drift apart.
 */
final class PublicPaths {

  static final String[] PATTERNS = {
    "/health", "/actuator/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
  };

  private PublicPaths() {}
}
