package org.ledger.harness;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/**
 * SPEC 0007's live counters (FR-33 columns) plus the results-table renderer. Postgres/Spring-free
 * by construction, so the table's formatting can be exercised without a container.
 */
public final class HarnessResults {

  public final LongAdder requestsFired = new LongAdder();
  public final LongAdder uniqueApplied = new LongAdder();
  public final LongAdder duplicatesDetected = new LongAdder();
  public final LongAdder clientResends = new LongAdder();

  private final List<String> hardFailures = new CopyOnWriteArrayList<>();

  public void recordHardFailure(String detail) {
    hardFailures.add(detail);
  }

  public List<String> hardFailures() {
    return Collections.unmodifiableList(hardFailures);
  }

  public String renderTable(
      String strategy,
      long seed,
      Duration wallClock,
      int maxInFlight,
      long moneyPre,
      long moneyPost,
      long globalEntrySum,
      long accountsBelowMinBalance,
      int sagasCrashed,
      int sagasRecoveredTerminal,
      int sagasCompleted,
      int sagasCompensated,
      boolean reconciliationDriftIsGenesisOnly,
      boolean overallPass) {
    long moneyDelta = moneyPost - moneyPre;
    StringBuilder sb = new StringBuilder();
    sb.append("\n┌─ CONCURRENCY & CHAOS HARNESS ─ strategy=")
        .append(strategy)
        .append(" ─ seed=")
        .append(seed)
        .append(" ─────────\n");
    sb.append(row("Requests fired", requestsFired.sum(), "wall clock", formatDuration(wallClock)));
    sb.append(
        row(
            "Unique transfers applied",
            uniqueApplied.sum(),
            "client re-sends",
            clientResends.sum()));
    sb.append(
        row("Duplicate requests detected", duplicatesDetected.sum(), "max in-flight", maxInFlight));
    sb.append(check("Double-charges", hardFailures.isEmpty() ? 0 : hardFailures.size()));
    sb.append("  Money delta                    ")
        .append(pad(moneyDelta))
        .append("   ")
        .append(moneyDelta == 0 ? "✔" : "✘")
        .append("  (pre ")
        .append(moneyPre)
        .append(" -> post ")
        .append(moneyPost)
        .append(")\n");
    sb.append(check("Σ(ledger_entries)", globalEntrySum));
    sb.append(check("Accounts below min_balance", accountsBelowMinBalance));
    sb.append("  Sagas crashed / recovered      ")
        .append(sagasCrashed)
        .append(" / ")
        .append(sagasRecoveredTerminal)
        .append("  ")
        .append(sagasCrashed == sagasRecoveredTerminal ? "✔" : "✘")
        .append("  (terminal: ")
        .append(sagasCompleted)
        .append(" COMPLETED, ")
        .append(sagasCompensated)
        .append(" COMPENSATED)\n");
    sb.append("  Reconciliation drift           ")
        .append(reconciliationDriftIsGenesisOnly ? "genesis only" : "UNEXPECTED DRIFT")
        .append(" ")
        .append(reconciliationDriftIsGenesisOnly ? "✔" : "✘")
        .append("\n");
    sb.append("  INVARIANT STATUS               ")
        .append(overallPass ? "PASS" : "FAIL")
        .append("\n");
    sb.append("└──────────────────────────────────────────────────────────────────────────────┘\n");
    return sb.toString();
  }

  private static String row(
      String leftLabel, long leftValue, String rightLabel, Object rightValue) {
    return "  "
        + padLabel(leftLabel)
        + pad(leftValue)
        + "    "
        + padLabel(rightLabel)
        + rightValue
        + "\n";
  }

  private static String check(String label, long value) {
    return "  " + padLabel(label) + pad(value) + "   " + (value == 0 ? "✔" : "✘") + "\n";
  }

  private static String padLabel(String label) {
    return String.format("%-30s", label);
  }

  private static String pad(long value) {
    return String.format("%,10d", value);
  }

  private static String formatDuration(Duration d) {
    long minutes = d.toMinutes();
    long seconds = d.minusMinutes(minutes).getSeconds();
    return minutes + "m " + seconds + "s";
  }
}
