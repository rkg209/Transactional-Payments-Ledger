package org.ledger.api.pagination;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque, base64url-encoded {@code {createdAt, id}} pagination cursor (§5.2). Kept in {@code api}
 * rather than {@code account}: the cursor is a wire-format concern, and {@code account} must not
 * import {@code api} (planning/03-system-design.md §1.2). {@link org.ledger.account.AccountService}
 * takes the decoded {@code (OffsetDateTime, UUID)} pair, never this type.
 */
public record Cursor(OffsetDateTime createdAt, UUID id) {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  public String encode() {
    try {
      byte[] json = MAPPER.writeValueAsBytes(new Payload(createdAt, id));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to encode cursor", e);
    }
  }

  public static Cursor decode(String encoded) {
    try {
      byte[] json = Base64.getUrlDecoder().decode(encoded.getBytes(StandardCharsets.UTF_8));
      Payload payload = MAPPER.readValue(json, Payload.class);
      return new Cursor(payload.createdAt(), payload.id());
    } catch (Exception e) {
      throw new InvalidCursorException(encoded, e);
    }
  }

  private record Payload(OffsetDateTime createdAt, UUID id) {}
}
