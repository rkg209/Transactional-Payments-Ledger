package org.ledger.bench;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.LoggerFactory;

/**
 * Deterministic wrapper around JMH, invoked by {@code make bench} / {@code make bench-quick} (via
 * the {@code bench} Maven profile's {@code exec:exec}). Runs the full {@link TransferBenchmark}
 * matrix, then -- once JMH has returned control, not from a shutdown hook -- writes {@code
 * docs/bench/{results.md,results.csv,latency.svg}} from {@link BenchmarkSink}'s accumulated cells.
 * Preferred over invoking {@code org.openjdk.jmh.Main} directly: exit code, artifact-writing order,
 * and the {@code bench-quick} contention override are all explicit here rather than assembled from
 * shell/Main command-line args.
 */
public final class BenchRunner {

  public static void main(String[] args) throws RunnerException {
    silenceLogging();

    boolean quick = "quick".equals(System.getProperty("bench.profile"));

    // forks(0): run JMH embedded in this JVM, not a nested one -- see TransferBenchmark's
    // @Fork(0) javadoc for why a second fork here breaks Testcontainers' Docker handshake.
    ChainedOptionsBuilder builder =
        new OptionsBuilder()
            .include(TransferBenchmark.class.getSimpleName())
            .shouldFailOnError(true)
            .forks(0);

    if (quick) {
      // Fewer contention levels for a fast end-to-end smoke run; TransferBenchmark itself also
      // shrinks the warmup/measured burst size per thread under bench.profile=quick.
      builder = builder.param("contention", "1", "4", "16");
    }

    Options options = builder.build();
    try {
      new Runner(options).run();
    } finally {
      TransferBenchmark.closeAllContexts();
    }

    BenchmarkReport.write(BenchmarkSink.results());
  }

  /**
   * {@code application.yml} turns on {@code org.jooq.tools.LoggerListener: DEBUG} (useful for
   * debugging an IT, one request at a time) -- under this benchmark's concurrent bursts it would
   * log every bind variable of every statement on every thread, and the resulting I/O and
   * shared-appender contention would dominate measured latency, not the lock contention this
   * benchmark exists to measure. Silenced programmatically via the Logback API directly: Spring
   * Boot's {@code logging.level.*} properties are read once, early, from {@code
   * ApplicationEnvironmentPreparedEvent} -- before {@link BenchContext}'s property-source override
   * (applied later, from an {@code ApplicationContextInitializer}) would ever take effect.
   *
   * <p>{@code org.jooq.tools.LoggerListener} is set explicitly, not just root: an explicit level on
   * a named logger wins over an inherited root level, so silencing root alone leaves this one
   * logger still at DEBUG.
   */
  private static void silenceLogging() {
    ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.WARN);
    ((Logger) LoggerFactory.getLogger("org.jooq.tools.LoggerListener")).setLevel(Level.WARN);
  }

  private BenchRunner() {}
}
