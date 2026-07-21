package org.ledger.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.ledger.api.error.ErrorCode;
import org.ledger.api.error.ErrorResponseWriter;
import org.ledger.db.IdempotencyKeyRepository;
import org.ledger.db.generated.tables.records.IdempotencyKeysRecord;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Gates every mutating {@code /api/v1/**} request on its {@code Idempotency-Key} header. Registered
 * (see {@code SecurityConfig}) to run <b>after</b> {@code ApiKeyAuthFilter}, so an unauthenticated
 * request never writes an {@code idempotency_keys} row, and is itself never {@code @Transactional}
 * — the claim must commit before the guarded operation runs, or the loser would block on a row lock
 * for the operation's full duration instead of getting an immediate conflict signal.
 *
 * <p>A missing header is deliberately not handled here; it stays a {@code
 * MissingRequestHeaderException} → {@code 400 MISSING_IDEMPOTENCY_KEY} at the controller.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

  public static final String IDEMPOTENCY_KEY_ATTRIBUTE = "org.ledger.idempotency.key";
  public static final String REPLAYED_HEADER = "X-Idempotent-Replayed";
  private static final String HEADER = "Idempotency-Key";

  private static final Set<String> MUTATING_METHODS =
      Set.of(HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name());

  private static final long POLL_INTERVAL_MS = 25L;
  private static final long POLL_TIMEOUT_MS = 1000L;

  private final IdempotencyKeyRepository repository;
  private final FingerprintService fingerprintService;

  public IdempotencyFilter(
      IdempotencyKeyRepository repository, FingerprintService fingerprintService) {
    this.repository = repository;
    this.fingerprintService = fingerprintService;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getServletPath().startsWith("/api/v1/")
        || !MUTATING_METHODS.contains(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String key = request.getHeader(HEADER);
    if (key == null) {
      filterChain.doFilter(request, response);
      return;
    }

    byte[] bodyBytes = request.getInputStream().readAllBytes();
    String fingerprint = fingerprintService.sha256Hex(bodyBytes);
    CachedBodyHttpServletRequest wrappedRequest =
        new CachedBodyHttpServletRequest(request, bodyBytes);

    long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
    while (true) {
      if (repository.tryClaim(key, fingerprint)) {
        execute(key, wrappedRequest, response, filterChain);
        return;
      }

      IdempotencyKeysRecord existing =
          repository
              .find(key)
              .orElseThrow(() -> new IllegalStateException("Lost claim race for key " + key));

      if (!existing.getRequestFingerprint().equals(fingerprint)) {
        ErrorResponseWriter.write(response, ErrorCode.IDEMPOTENCY_KEY_REUSE, Map.of("key", key));
        return;
      }

      IdempotencyStatus status = IdempotencyStatus.valueOf(existing.getStatus());
      if (status == IdempotencyStatus.COMPLETED) {
        replay(response, existing);
        return;
      }
      if (status == IdempotencyStatus.FAILED) {
        if (repository.reclaimFailed(key, fingerprint)) {
          execute(key, wrappedRequest, response, filterChain);
          return;
        }
        continue;
      }

      // IN_PROGRESS: poll for the winner to resolve.
      if (System.currentTimeMillis() >= deadline) {
        ErrorResponseWriter.write(response, ErrorCode.IDEMPOTENCY_TIMEOUT, Map.of("key", key));
        return;
      }
      sleep();
    }
  }

  private void execute(
      String key,
      CachedBodyHttpServletRequest wrappedRequest,
      HttpServletResponse response,
      FilterChain filterChain)
      throws ServletException, IOException {
    wrappedRequest.setAttribute(IDEMPOTENCY_KEY_ATTRIBUTE, key);
    ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
    try {
      filterChain.doFilter(wrappedRequest, wrappedResponse);
      if (wrappedResponse.getStatus() >= 500) {
        repository.markFailed(key);
      } else {
        String snapshot =
            new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
        repository.markCompleted(key, snapshot);
      }
    } catch (ServletException | IOException | RuntimeException e) {
      repository.markFailed(key);
      throw e;
    } finally {
      wrappedResponse.copyBodyToResponse();
    }
  }

  private void replay(HttpServletResponse response, IdempotencyKeysRecord record)
      throws IOException {
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setHeader(REPLAYED_HEADER, "true");
    String snapshot = Optional.ofNullable(record.getResponseSnapshot()).orElse("");
    response.getWriter().write(snapshot);
  }

  private void sleep() {
    try {
      Thread.sleep(POLL_INTERVAL_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while polling idempotency key", e);
    }
  }
}
