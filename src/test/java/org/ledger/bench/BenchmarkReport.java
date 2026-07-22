package org.ledger.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Pure formatter over accumulated matrix cells -- no Spring, no Postgres -- so the crossover
 * detection and percentage math ({@code BenchmarkReportTest}) are unit-testable without a
 * container. This is the one piece of bench logic that can silently produce a wrong headline
 * number, hence the dedicated test.
 */
public final class BenchmarkReport {

  public record Cell(
      String strategy,
      String isolation,
      int contention,
      double opsPerSec,
      long p50Millis,
      long p99Millis,
      long p999Millis,
      long successes,
      long retryExhausted,
      long otherFailures) {}

  private static final List<String> ISOLATIONS = List.of("read_committed", "serializable");
  private static final List<String> STRATEGIES = List.of("optimistic", "pessimistic");

  /**
   * {@code TransferBenchmark} has {@code @Measurement(iterations = 3)}: {@link BenchmarkSink}
   * records one {@link Cell} per JMH measurement iteration, so the raw list passed in here holds 3
   * rows per (strategy, isolation, contention) combination, not 1. Collapses those 3 into a single
   * per-cell {@link Cell} (median throughput/latency, summed counts) before anything -- a table,
   * crossover, or cost percentage -- is computed from it. Without this, {@link #opsByContention}
   * would silently keep only the last of the 3 rows for a given key (a {@code Map} overwrite), so
   * two-thirds of every cell's data would be discarded without any error.
   */
  public static List<Cell> aggregateByCell(List<Cell> cells) {
    Map<String, List<Cell>> byCell = new LinkedHashMap<>();
    for (Cell c : cells) {
      byCell
          .computeIfAbsent(
              c.strategy() + "|" + c.isolation() + "|" + c.contention(), k -> new ArrayList<>())
          .add(c);
    }
    List<Cell> aggregated = new ArrayList<>();
    for (List<Cell> group : byCell.values()) {
      aggregated.add(medianOf(group));
    }
    return aggregated;
  }

  private static Cell medianOf(List<Cell> group) {
    Cell first = group.get(0);
    return new Cell(
        first.strategy(),
        first.isolation(),
        first.contention(),
        medianDouble(group.stream().map(Cell::opsPerSec).toList()),
        medianLong(group.stream().map(Cell::p50Millis).toList()),
        medianLong(group.stream().map(Cell::p99Millis).toList()),
        medianLong(group.stream().map(Cell::p999Millis).toList()),
        group.stream().mapToLong(Cell::successes).sum(),
        group.stream().mapToLong(Cell::retryExhausted).sum(),
        group.stream().mapToLong(Cell::otherFailures).sum());
  }

  private static double medianDouble(List<Double> samples) {
    List<Double> sorted = new ArrayList<>(samples);
    sorted.sort(Comparator.naturalOrder());
    return sorted.get(sorted.size() / 2);
  }

  private static long medianLong(List<Long> samples) {
    List<Long> sorted = new ArrayList<>(samples);
    sorted.sort(Comparator.naturalOrder());
    return sorted.get(sorted.size() / 2);
  }

  /**
   * The lowest contention level at which pessimistic transfers/sec is greater than or equal to
   * optimistic's, and stays greater than or equal to it for every higher contention level actually
   * measured. Empty when no such level exists in the swept range -- SPEC 0008's "report the data"
   * branch, not an error.
   */
  public static Optional<Integer> crossoverContention(List<Cell> cells, String isolation) {
    Map<Integer, Double> optimistic = opsByContention(cells, "optimistic", isolation);
    Map<Integer, Double> pessimistic = opsByContention(cells, "pessimistic", isolation);
    List<Integer> levels =
        optimistic.keySet().stream().filter(pessimistic::containsKey).sorted().toList();

    for (int i = 0; i < levels.size(); i++) {
      boolean holdsForEveryHigherLevel = true;
      for (int j = i; j < levels.size(); j++) {
        int level = levels.get(j);
        if (pessimistic.get(level) < optimistic.get(level)) {
          holdsForEveryHigherLevel = false;
          break;
        }
      }
      if (holdsForEveryHigherLevel) {
        return Optional.of(levels.get(i));
      }
    }
    return Optional.empty();
  }

  /**
   * Average throughput cost of SERIALIZABLE vs READ COMMITTED for one strategy, as a percentage,
   * averaged over every contention level measured under both isolation levels.
   */
  public static double serializableCostPercent(List<Cell> cells, String strategy) {
    Map<Integer, Double> readCommitted = opsByContention(cells, strategy, "read_committed");
    Map<Integer, Double> serializable = opsByContention(cells, strategy, "serializable");
    double costPercentSum = 0;
    int n = 0;
    for (Map.Entry<Integer, Double> entry : readCommitted.entrySet()) {
      Double serializableOps = serializable.get(entry.getKey());
      double readCommittedOps = entry.getValue();
      if (serializableOps != null && readCommittedOps > 0) {
        costPercentSum += (readCommittedOps - serializableOps) / readCommittedOps * 100.0;
        n++;
      }
    }
    return n == 0 ? 0.0 : costPercentSum / n;
  }

  private static Map<Integer, Double> opsByContention(
      List<Cell> cells, String strategy, String isolation) {
    Map<Integer, Double> byContention = new TreeMap<>();
    for (Cell c : cells) {
      if (c.strategy().equals(strategy) && c.isolation().equals(isolation)) {
        byContention.put(c.contention(), c.opsPerSec());
      }
    }
    return byContention;
  }

  public static String claim(List<Cell> cells) {
    if (cells.isEmpty()) {
      return "No data collected.";
    }
    Cell bestSerializable =
        cells.stream()
            .filter(c -> c.isolation().equals("serializable"))
            .max(Comparator.comparingDouble(Cell::opsPerSec))
            .orElseGet(() -> cells.stream().max(Comparator.comparingDouble(Cell::opsPerSec)).get());
    Optional<Integer> crossover = crossoverContention(cells, "read_committed");
    return String.format(
        "Sustained %.0f transfers/sec at p99 %d ms under %s isolation (%s strategy, contention"
            + " %d threads/hot account); %s.",
        bestSerializable.opsPerSec(),
        bestSerializable.p99Millis(),
        bestSerializable.isolation(),
        bestSerializable.strategy(),
        bestSerializable.contention(),
        crossover
            .map(
                c ->
                    "optimistic wins below contention level "
                        + c
                        + ", pessimistic at or above it (read_committed)")
            .orElse(
                "optimistic held the lead across the full measured range under read_committed"));
  }

  public static String renderMarkdown(List<Cell> cells) {
    StringBuilder sb = new StringBuilder();
    sb.append("# SPEC 0008 -- benchmark results\n\n");
    sb.append(
        "Each row is the median of 3 JMH measurement iterations for that (strategy, isolation,"
            + " contention) cell; success/retry/failure counts are summed across the 3.\n\n");
    sb.append(
        "| strategy | isolation | contention | transfers/sec | p50 (ms) | p99 (ms) | p999 (ms) |"
            + " successes | retry exhausted | other failures |\n");
    sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
    for (Cell c : sortedForDisplay(cells)) {
      sb.append("| ")
          .append(c.strategy())
          .append(" | ")
          .append(c.isolation())
          .append(" | ")
          .append(c.contention())
          .append(" | ")
          .append(String.format("%.1f", c.opsPerSec()))
          .append(" | ")
          .append(c.p50Millis())
          .append(" | ")
          .append(c.p99Millis())
          .append(" | ")
          .append(c.p999Millis())
          .append(" | ")
          .append(c.successes())
          .append(" | ")
          .append(c.retryExhausted())
          .append(" | ")
          .append(c.otherFailures())
          .append(" |\n");
    }
    sb.append('\n');

    for (String isolation : ISOLATIONS) {
      Optional<Integer> crossover = crossoverContention(cells, isolation);
      sb.append("**Crossover (")
          .append(isolation)
          .append("):** ")
          .append(
              crossover
                  .map(
                      c ->
                          "pessimistic overtakes optimistic at "
                              + c
                              + " threads/hot account and stays ahead through the swept range")
                  .orElse("no crossover found within the contention range measured"))
          .append("\n\n");
    }

    for (String strategy : STRATEGIES) {
      double cost = serializableCostPercent(cells, strategy);
      sb.append("**SERIALIZABLE cost (")
          .append(strategy)
          .append("):** ")
          .append(String.format("%.1f%%", cost))
          .append(" average throughput reduction vs READ COMMITTED\n\n");
    }

    sb.append("## Claim\n\n").append(claim(cells)).append("\n\n");
    sb.append("## Issues / surprises\n\n");
    sb.append("See progress_report.md for this spec's entry.\n");
    return sb.toString();
  }

  public static String renderCsv(List<Cell> cells) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "strategy,isolation,contention,ops_per_sec,p50_ms,p99_ms,p999_ms,successes,"
            + "retry_exhausted,other_failures\n");
    for (Cell c : sortedForDisplay(cells)) {
      sb.append(c.strategy())
          .append(',')
          .append(c.isolation())
          .append(',')
          .append(c.contention())
          .append(',')
          .append(String.format("%.2f", c.opsPerSec()))
          .append(',')
          .append(c.p50Millis())
          .append(',')
          .append(c.p99Millis())
          .append(',')
          .append(c.p999Millis())
          .append(',')
          .append(c.successes())
          .append(',')
          .append(c.retryExhausted())
          .append(',')
          .append(c.otherFailures())
          .append('\n');
    }
    return sb.toString();
  }

  private static List<Cell> sortedForDisplay(List<Cell> cells) {
    List<Cell> sorted = new ArrayList<>(cells);
    sorted.sort(
        Comparator.comparing(Cell::strategy)
            .thenComparing(Cell::isolation)
            .thenComparingInt(Cell::contention));
    return sorted;
  }

  /** {@code cells} is the raw, un-aggregated list -- see {@link #aggregateByCell}. */
  public static void write(List<Cell> cells) {
    List<Cell> aggregated = aggregateByCell(cells);
    try {
      Path dir = Path.of("docs", "bench");
      Files.createDirectories(dir);
      Files.writeString(dir.resolve("results.md"), renderMarkdown(aggregated));
      Files.writeString(dir.resolve("results.csv"), renderCsv(aggregated));
      Files.writeString(
          dir.resolve("latency.svg"),
          SvgLatencyPlot.render(
              aggregated, crossoverContention(aggregated, "read_committed").orElse(null)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private BenchmarkReport() {}
}
