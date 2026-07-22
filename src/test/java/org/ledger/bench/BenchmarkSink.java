package org.ledger.bench;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects one {@link BenchmarkReport.Cell} per matrix cell as {@code TransferBenchmark} runs. */
final class BenchmarkSink {

  private static final List<BenchmarkReport.Cell> CELLS = new CopyOnWriteArrayList<>();

  static void record(
      String strategy,
      String isolation,
      int contention,
      LatencyRecorder recorder,
      Duration measuredWallClock) {
    long[] sorted = recorder.sortedMerged();
    double measuredSeconds = Math.max(measuredWallClock.toNanos() / 1_000_000_000.0, 1e-9);
    double opsPerSec = recorder.successes.sum() / measuredSeconds;
    CELLS.add(
        new BenchmarkReport.Cell(
            strategy,
            isolation,
            contention,
            opsPerSec,
            toMillis(LatencyRecorder.percentile(sorted, 50)),
            toMillis(LatencyRecorder.percentile(sorted, 99)),
            toMillis(LatencyRecorder.percentile(sorted, 99.9)),
            recorder.successes.sum(),
            recorder.retryExhausted.sum(),
            recorder.otherFailures.sum()));
  }

  static List<BenchmarkReport.Cell> results() {
    return List.copyOf(CELLS);
  }

  private static long toMillis(long nanos) {
    return Math.round(nanos / 1_000_000.0);
  }

  private BenchmarkSink() {}
}
