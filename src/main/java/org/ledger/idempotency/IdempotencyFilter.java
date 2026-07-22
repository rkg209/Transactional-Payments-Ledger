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
import org.ledger.infrastructure.LedgerIdempotencyProperties;
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
  private final LedgerIdempotencyProperties idempotencyProperties;

  public IdempotencyFilter(
      IdempotencyKeyRepository repository,
      FingerprintService fingerprintService,
      LedgerIdempotencyProperties idempotencyProperties) {
    this.repository = repository;
    this.fingerprintService = fingerprintService;
    this.idempotencyProperties = idempotencyProperties;
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
    boolean staleReclaimAttempted = false;
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
        // SPEC 0010 / ADR 0012. The "winner" may not be running at all: a process that died
        // between tryClaim and its terminal markCompleted/markFailed leaves this row IN_PROGRESS
        // with nobody left to resolve it. Without this, that key is poisoned permanently -- every
        // future retry lands right here and 503s, so one specific payment can never be made again.
        // Only a claim older than staleClaimAfter is reclaimable, and the predicate is evaluated
        // in SQL so a genuine in-flight request cannot be stolen out from under itself.
        if (!staleReclaimAttempted) {
          staleReclaimAttempted = true;
          if (repository.reclaimStale(key, fingerprint, idempotencyProperties.staleClaimAfter())) {
            execute(key, wrappedRequest, response, filterChain);
            return;
          }
          // 0 rows updated means one of two things, and both are worth one more poll window rather
          // than an immediate 503: either the claim is not stale yet (a genuine in-flight twin), or
          // a concurrent retry just won the reclaim and is executing now. In the second case the
          // winner is about to write a response this request can replay, so giving up here would
          // 503 a caller whose answer is seconds away.
          deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
          sleep();
          continue;
        }
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
      // 409 CONFLICT_RETRY_EXHAUSTED is a 4xx but, unlike INSUFFICIENT_FUNDS or
      // SAME_ACCOUNT_TRANSFER, it is not a deterministic property of the request -- it is
      // TransferService reporting that it could not win its own internal lock/CAS race within its
      // retry budget this time. Caching it as COMPLETED would freeze that outcome forever: replay()
      // always returns 200, so every future retry with this key would silently replay the stale 409
      // rather than getting a real chance to succeed once contention subsides (ADR 0009).
      if (wrappedResponse.getStatus() >= 500
          || wrappedResponse.getStatus() == HttpServletResponse.SC_CONFLICT) {
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
