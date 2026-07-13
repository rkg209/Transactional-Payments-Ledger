package org.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Transactional Payments Ledger.
 *
 * <p>A double-entry ledger whose deliverable is a proven correctness guarantee: no money lost,
 * created, or double-charged under concurrency and crashes.
 *
 * <p>Virtual threads are enabled via {@code spring.threads.virtual.enabled} in application.yml, so
 * each request gets its own carrier-free thread and blocking on a database lock does not pin a
 * platform thread.
 */
@SpringBootApplication
public class LedgerApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerApplication.class, args);
  }
}
