package org.ledger.idempotency;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

/**
 * SHA-256 of the raw request body, always rendered as 64 lowercase hex characters (satisfies {@code
 * idempotency_keys_fingerprint_len}).
 */
@Component
public class FingerprintService {

  public String sha256Hex(byte[] body) {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
    byte[] hash = digest.digest(body);
    StringBuilder hex = new StringBuilder(hash.length * 2);
    for (byte b : hash) {
      hex.append(Character.forDigit((b >> 4) & 0xF, 16));
      hex.append(Character.forDigit(b & 0xF, 16));
    }
    return hex.toString();
  }
}
