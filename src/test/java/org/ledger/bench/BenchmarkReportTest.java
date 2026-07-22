package org.ledger.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no Spring, no container: {@link BenchmarkReport} is Postgres/Spring-free by
 * construction so its crossover detection and percentage math -- the one part of the bench that can
 * silently produce a wrong headline number -- is checkable without a full benchmark run.
 */
class BenchmarkReportTest {

  private static BenchmarkReport.Cell cell(
      String strategy, String isolation, int contention, double opsPerSec) {
    return new BenchmarkReport.Cell(strategy, isolation, contention, opsPerSec, 1, 2, 3, 100, 0, 0);
  }

  @Test
  void crossoverIsTheLowestLevelWherePessimisticStaysAheadForEveryHigherLevel() {
    List<BenchmarkReport.Cell> cells =
        List.of(
            cell("optimistic", "read_committed", 1, 1000),
            cell("optimistic", "read_committed", 4, 800),
            cell("optimistic", "read_committed", 16, 200),
            cell("pessimistic", "read_committed", 1, 500),
            cell("pessimistic", "read_committed", 4, 700),
            cell("pessimistic", "read_committed", 16, 400));

    assertThat(BenchmarkReport.crossoverContention(cells, "read_committed")).contains(16);
  }

  @Test
  void crossoverMustHoldForEveryHigherLevelNotJustOne() {
    List<BenchmarkReport.Cell> cells =
        List.of(
            cell("optimistic", "read_committed", 1, 100),
            cell("optimistic", "read_committed", 2, 100),
            cell("optimistic", "read_committed", 4, 100),
            cell("pessimistic", "read_committed", 1, 50),
            // Momentarily overtakes at 2, then falls back below optimistic at 4 -- 2 must NOT be
            // reported as the crossover; "stays ahead" is the whole point of the definition.
            cell("pessimistic", "read_committed", 2, 150),
            cell("pessimistic", "read_committed", 4, 50));

    assertThat(BenchmarkReport.crossoverContention(cells, "read_committed")).isEmpty();
  }

  @Test
  void noCrossoverWhenOptimisticNeverLoses() {
    List<BenchmarkReport.Cell> cells =
        List.of(
            cell("optimistic", "read_committed", 1, 1000),
            cell("optimistic", "read_committed", 64, 900),
            cell("pessimistic", "read_committed", 1, 500),
            cell("pessimistic", "read_committed", 64, 600));

    assertThat(BenchmarkReport.crossoverContention(cells, "read_committed")).isEmpty();
  }

  @Test
  void serializableCostIsAveragedAcrossContentionLevels() {
    List<BenchmarkReport.Cell> cells =
        List.of(
            cell("optimistic", "read_committed", 1, 100),
            cell("optimistic", "serializable", 1, 90),
            cell("optimistic", "read_committed", 4, 200),
            cell("optimistic", "serializable", 4, 150));

    // level 1: (100-90)/100 = 10%; level 4: (200-150)/200 = 25%; average = 17.5%
    assertThat(BenchmarkReport.serializableCostPercent(cells, "optimistic")).isEqualTo(17.5);
  }

  @Test
  void serializableCostIsZeroWithNoMatchingData() {
    assertThat(BenchmarkReport.serializableCostPercent(List.of(), "optimistic")).isZero();
  }

  @Test
  void claimReportsNoDataOnEmptyResults() {
    assertThat(BenchmarkReport.claim(List.of())).isEqualTo("No data collected.");
  }

  @Test
  void aggregateByCellCollapsesRepeatedMeasurementIterationsToOneRowPerCell() {
    // TransferBenchmark's @Measurement(iterations = 3) means BenchmarkSink records 3 raw Cells
    // per (strategy, isolation, contention) combination -- aggregateByCell must collapse each
    // group of 3 into exactly 1, or crossover/cost math silently keeps only the last of the 3.
    List<BenchmarkReport.Cell> raw =
        List.of(
            cell("optimistic", "read_committed", 1, 100),
            cell("optimistic", "read_committed", 1, 300),
            cell("optimistic", "read_committed", 1, 200),
            cell("pessimistic", "read_committed", 1, 50));

    List<BenchmarkReport.Cell> aggregated = BenchmarkReport.aggregateByCell(raw);

    assertThat(aggregated).hasSize(2);
    BenchmarkReport.Cell optimistic =
        aggregated.stream()
            .filter(c -> c.strategy().equals("optimistic"))
            .findFirst()
            .orElseThrow();
    assertThat(optimistic.opsPerSec()).isEqualTo(200); // median of 100, 200, 300
  }

  @Test
  void aggregateByCellSumsCountsAcrossIterations() {
    List<BenchmarkReport.Cell> raw =
        List.of(
            new BenchmarkReport.Cell("optimistic", "read_committed", 1, 100, 1, 2, 3, 10, 2, 1),
            new BenchmarkReport.Cell("optimistic", "read_committed", 1, 100, 1, 2, 3, 20, 3, 0));

    BenchmarkReport.Cell aggregated = BenchmarkReport.aggregateByCell(raw).get(0);

    assertThat(aggregated.successes()).isEqualTo(30);
    assertThat(aggregated.retryExhausted()).isEqualTo(5);
    assertThat(aggregated.otherFailures()).isEqualTo(1);
  }
}
