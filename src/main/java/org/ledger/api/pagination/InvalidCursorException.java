package org.ledger.api.pagination;

/**
 * Thrown when a client-supplied {@code cursor} query parameter cannot be decoded. Maps to {@code
 * 400 VALIDATION_ERROR}.
 */
public class InvalidCursorException extends RuntimeException {

  public InvalidCursorException(String cursor, Throwable cause) {
    super("Malformed pagination cursor: " + cursor, cause);
  }
}
