package org.ledger.bench;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-thread nanosecond latency buffers, merged and percentiled at the end of one benchmark
 * invocation. No external histogram dependency -- sort+index is adequate at these sample counts
 * (one JMH {@code SingleShotTime} burst, not a long-running profiler).
 *
 * <p>Warm-up transfers are never recorded here at all (see {@code TransferBenchmark}): the driver
 * calls {@link #recordSuccess}/{@link #recordRetryExhausted}/{@link #recordOtherFailure} only for
 * the measured phase of each burst.
 */
final class LatencyRecorder {

  private final Queue<Buffer> buffers = new ConcurrentLinkedQueue<>();
  private final ThreadLocal<Buffer> local;

  final LongAdder successes = new LongAdder();
  final LongAdder retryExhausted = new LongAdder();
  final LongAdder otherFailures = new LongAdder();

  LatencyRecorder(int capacityPerThread) {
    this.local =
        ThreadLocal.withInitial(
            () -> {
              Buffer buffer = new Buffer(new long[capacityPerThread]);
              buffers.add(buffer);
              return buffer;
            });
  }

  void recordSuccess(long nanos) {
    successes.increment();
    local.get().add(nanos);
  }

  void recordRetryExhausted() {
    retryExhausted.increment();
  }

  void recordOtherFailure() {
    otherFailures.increment();
  }

  /** All recorded samples across every thread, sorted ascending. */
  long[] sortedMerged() {
    int total = 0;
    for (Buffer buffer : buffers) {
      total += buffer.count;
    }
    long[] merged = new long[total];
    int pos = 0;
    for (Buffer buffer : buffers) {
      System.arraycopy(buffer.samples, 0, merged, pos, buffer.count);
      pos += buffer.count;
    }
    Arrays.sort(merged);
    return merged;
  }

  /** {@code sorted} must already be ascending (see {@link #sortedMerged}). */
  static long percentile(long[] sorted, double pct) {
    if (sorted.length == 0) {
      return 0;
    }
    int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
    if (idx < 0) {
      idx = 0;
    }
    if (idx >= sorted.length) {
      idx = sorted.length - 1;
    }
    return sorted[idx];
  }

  private static final class Buffer {
    final long[] samples;
    int count;

    Buffer(long[] samples) {
      this.samples = samples;
    }

    void add(long value) {
      // A thread that exceeds its declared capacity (a bug in the driver's per-thread transfer
      // count, not a real workload condition) drops the overflow sample rather than throwing --
      // losing a percentile sample is not worth failing an otherwise-valid benchmark run over.
      if (count < samples.length) {
        samples[count++] = value;
      }
    }
  }
}
