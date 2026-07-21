package org.ledger.infrastructure;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.ledger.idempotency.IdempotencyFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Wires the API-key {@link SecurityFilterChain} (ADR 0003). CSRF is disabled and the session policy
 * is stateless: there is no browser session and no cookie to protect. {@code /health}, the actuator
 * endpoints, and the springdoc/Swagger routes are deliberately unauthenticated; everything under
 * {@code /api/v1/**} requires a valid key, and {@code anyRequest()} ends in {@code denyAll()} so a
 * route added outside that prefix fails closed rather than open.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ApiKeyAuthFilter apiKeyAuthFilter,
      IdempotencyFilter idempotencyFilter,
      ApiKeyAuthenticationEntryPoint entryPoint)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(PublicPaths.PATTERNS)
                    .permitAll()
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
        .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(idempotencyFilter, ApiKeyAuthFilter.class);
    return http.build();
  }

  @Bean
  public ApiKeyAuthFilter apiKeyAuthFilter(@Value("${ledger.api-keys}") String apiKeysProperty) {
    return new ApiKeyAuthFilter(parseKeys(apiKeysProperty));
  }

  private static Set<String> parseKeys(String apiKeysProperty) {
    Set<String> keys = new LinkedHashSet<>();
    Arrays.stream(apiKeysProperty.split(","))
        .map(String::trim)
        .filter(key -> !key.isEmpty())
        .forEach(keys::add);
    return Set.copyOf(keys);
  }
}
