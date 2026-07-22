package org.ledger.bench;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.ledger.transfer.OptimisticLockException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * SPEC 0008's headline JMH entry point: the 2x2 {@code ConcurrencyStrategy x isolation} matrix,
 * swept across contention levels, timing {@code TransferService.execute(...)} directly (not over
 * HTTP -- Tomcat + {@code IdempotencyFilter} would dominate and blur the crossover).
 *
 * <p>{@code SingleShotTime}: one invocation of {@link #transferBurst()} is one full burst of {@code
 * contention} virtual threads racing on one hot account pair. Throughput and percentiles are
 * computed by {@link LatencyRecorder} / {@link BenchmarkSink}, not by JMH's own statistics, so the
 * contention sweep can be a {@code @Param} and the output is the results table SPEC 0008 asks for.
 *
 * <p><b>Warm-up is done inside each invocation, not by JMH</b> ({@code @Warmup(iterations = 0)}):
 * JMH gives a benchmark method no way to know whether the current iteration is a warmup one, and
 * the recorder must discard warmup samples. Each thread fires {@code WARMUP_TRANSFERS_PER_THREAD}
 * transfers with recording off, then crosses a {@link CyclicBarrier} before the measured phase
 * starts -- so the measured wall clock (used for transfers/sec) covers only the measured burst, not
 * warmup mixed in with it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.MILLISECONDS)
// 0, not 1: JMH's own fork spawns a nested JVM that does NOT inherit -Dapi.version /
// -Dnet.bytebuddy.experimental (Testcontainers then fails with a misleading "no Docker
// environment" -- see pom.xml's `bench` profile comment and BenchRunner). exec:exec already forks
// one controlled JVM; running JMH embedded in it, not forking a second time, is what lets the
// Docker API pin reach the JVM that actually starts BenchPostgres.
@Fork(0)
@Warmup(iterations = 0)
@Measurement(iterations = 3)
public class TransferBenchmark {

  private static final boolean QUICK = "quick".equals(System.getProperty("bench.profile"));
  private static final int WARMUP_TRANSFERS_PER_THREAD = QUICK ? 3 : 10;
  private static final int MEASURED_TRANSFERS_PER_THREAD = QUICK ? 10 : 30;
  private static final long AMOUNT_MINOR = 10L;

  /** Comfortably above the highest contention level swept (64), for every cached context. */
  private static final int POOL_SIZE = 96;

  /**
   * One {@link BenchContext} per (strategy, isolation) pair, reused across every contention level
   * swept for that pair -- ConcurrencyConfig has no runtime switch, so re-booting Spring per
   * contention level (28 boots instead of 4) would dominate the run time for nothing.
   */
  private static final Map<String, BenchContext> CONTEXTS = new ConcurrentHashMap<>();

  @Param({"optimistic", "pessimistic"})
  public String strategy;

  @Param({"read_committed", "serializable"})
  public String isolation;

  @Param({"1", "2", "4", "8", "16", "32", "64"})
  public int contention;

  private BenchContext context;
  private BenchFixture fixture;
  private LatencyRecorder recorder;

  @Setup(Level.Trial)
  public void setUpTrial() {
    context =
        CONTEXTS.computeIfAbsent(
            strategy + ":" + isolation, key -> BenchContext.boot(strategy, isolation, POOL_SIZE));
  }

  @Setup(Level.Iteration)
  public void setUpIteration() {
    long transfersPerThread = WARMUP_TRANSFERS_PER_THREAD + MEASURED_TRANSFERS_PER_THREAD;
    // 2x headroom: a thread's transfers can interleave with others on the *destination* side too
    // once bidirectional traffic exists, but today's workload is strictly source -> hot, so 1x
    // would suffice; 2x is cheap insurance against ever tightening this without re-deriving it.
    long sourceStartBalance = contention * transfersPerThread * AMOUNT_MINOR * 2;
    fixture = BenchFixture.seed(context, sourceStartBalance);
    recorder = new LatencyRecorder(MEASURED_TRANSFERS_PER_THREAD);
  }

  @Benchmark
  public void transferBurst() throws InterruptedException, ExecutionException {
    CountDownLatch startGate = new CountDownLatch(1);
    CyclicBarrier warmupDone = new CyclicBarrier(contention);
    long[] measuredStartNanos = new long[1];

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<?>> futures = new ArrayList<>(contention);
    try {
      for (int t = 0; t < contention; t++) {
        futures.add(
            executor.submit(
                () -> {
                  awaitUninterruptibly(startGate);
                  for (int i = 0; i < WARMUP_TRANSFERS_PER_THREAD; i++) {
                    attemptTransfer(false);
                  }
                  awaitBarrierUninterruptibly(warmupDone);
                  synchronized (measuredStartNanos) {
                    if (measuredStartNanos[0] == 0) {
                      measuredStartNanos[0] = System.nanoTime();
                    }
                  }
                  for (int i = 0; i < MEASURED_TRANSFERS_PER_THREAD; i++) {
                    attemptTransfer(true);
                  }
                }));
      }
      startGate.countDown();
      for (Future<?> f : futures) {
        f.get();
      }
    } finally {
      executor.shutdown();
    }

    Duration measuredWallClock = Duration.ofNanos(System.nanoTime() - measuredStartNanos[0]);
    BenchmarkSink.record(strategy, isolation, contention, recorder, measuredWallClock);
  }

  private void attemptTransfer(boolean measured) {
    long startNanos = measured ? System.nanoTime() : 0;
    try {
      context.transferService.execute(
          fixture.sourceId,
          fixture.hotId,
          AMOUNT_MINOR,
          BenchFixture.CURRENCY,
          UUID.randomUUID().toString());
      if (measured) {
        recorder.recordSuccess(System.nanoTime() - startNanos);
      }
    } catch (OptimisticLockException e) {
      if (measured) {
        recorder.recordRetryExhausted();
      }
    } catch (RuntimeException e) {
      if (measured) {
        recorder.recordOtherFailure();
      }
    }
  }

  /**
   * Correctness gate on the benchmark itself: a throughput number from a run that lost money is
   * worthless. Checks the two accounts this trial actually wrote to, not the whole-ledger
   * reconciliation report -- {@code ReconciliationService} would also flag the genesis account's
   * known, constant, entry-less seed as "drift", which is expected (see {@code
   * AbstractPostgresIT#createGenesisAccount}) and not what this gate is for.
   */
  @TearDown(Level.Trial)
  public void tearDownTrial() {
    long globalSum = context.ledgerEntryRepository.globalEntrySum();
    long sourceBalance = context.accountService.getAccount(fixture.sourceId).balance();
    long sourceEntrySum = context.ledgerEntryRepository.entrySumForAccount(fixture.sourceId);
    long hotBalance = context.accountService.getAccount(fixture.hotId).balance();
    long hotEntrySum = context.ledgerEntryRepository.entrySumForAccount(fixture.hotId);

    boolean ok = globalSum == 0 && sourceBalance == sourceEntrySum && hotBalance == hotEntrySum;
    if (!ok) {
      throw new IllegalStateException(
          "Ledger invariant violated at end of trial strategy="
              + strategy
              + " isolation="
              + isolation
              + " contention="
              + contention
              + ": globalSum="
              + globalSum
              + " source(balance="
              + sourceBalance
              + ", entrySum="
              + sourceEntrySum
              + ") hot(balance="
              + hotBalance
              + ", entrySum="
              + hotEntrySum
              + ")");
    }
  }

  /** Closes every cached Spring context. Called once by {@code BenchRunner} after JMH returns. */
  static void closeAllContexts() {
    CONTEXTS.values().forEach(BenchContext::close);
    CONTEXTS.clear();
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static void awaitBarrierUninterruptibly(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (java.util.concurrent.BrokenBarrierException e) {
      throw new RuntimeException(e);
    }
  }
}
