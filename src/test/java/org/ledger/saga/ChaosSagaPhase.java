package org.ledger.saga;

import java.util.UUID;

/**
 * SPEC 0007's Phase 2 bridge into {@code SagaOrchestrator}'s crash-simulation seam. Declared in
 * package {@code org.ledger.saga} — same trick {@code SagaCrashRecoveryIT} uses — because {@link
 * SagaOrchestrator#setStepCommittedHookForTesting} is package-private and the harness itself
 * (package {@code org.ledger.harness}) cannot reach it.
 *
 * <p>Unlike {@code SagaCrashRecoveryIT}, several sagas crash concurrently on independent virtual
 * threads here, so one shared hook cannot key off a single {@code volatile} step index. A saga's id
 * does not exist until {@link SagaOrchestrator#execute} is already running, so it cannot be known
 * before the call either — a {@link ThreadLocal} sidesteps both problems: the hook always runs
 * synchronously on the same thread that called {@code execute()} (SPEC 0006 design notes: there is
 * no cross-thread handoff inside one saga), so a thread-scoped "what step should I crash at, and
 * which saga id did I actually crash" pair is exactly as safe as keying by {@code ctx.sagaId()}
 * would be, without needing that id in advance.
 */
public final class ChaosSagaPhase {

  private final SagaOrchestrator sagaOrchestrator;

  private final ThreadLocal<Integer> crashAfterStepIndex = new ThreadLocal<>();
  private final ThreadLocal<UUID> capturedSagaId = new ThreadLocal<>();

  public ChaosSagaPhase(SagaOrchestrator sagaOrchestrator) {
    this.sagaOrchestrator = sagaOrchestrator;
  }

  public void installHook() {
    sagaOrchestrator.setStepCommittedHookForTesting(
        (ctx, stepIndex) -> {
          Integer target = crashAfterStepIndex.get();
          if (target != null && target == stepIndex) {
            capturedSagaId.set(ctx.sagaId());
            throw new RuntimeException("chaos harness: simulated crash after step " + stepIndex);
          }
        });
  }

  /** Always call in a {@code finally} — a live hook would sabotage {@code recover()}. */
  public void uninstallHook() {
    sagaOrchestrator.setStepCommittedHookForTesting(null);
  }

  /**
   * Runs {@code definition} to a simulated crash right after step {@code crashStepIndex} commits,
   * on the calling thread, and returns the id of the saga that crashed.
   */
  public UUID crashOneSaga(SagaDefinition definition, int crashStepIndex) {
    crashAfterStepIndex.set(crashStepIndex);
    capturedSagaId.remove();
    try {
      sagaOrchestrator.execute(definition);
      throw new IllegalStateException(
          "expected a simulated crash after step " + crashStepIndex + " but the saga completed");
    } catch (RuntimeException e) {
      UUID sagaId = capturedSagaId.get();
      if (sagaId == null) {
        throw new IllegalStateException(
            "saga failed before reaching the intended crash step " + crashStepIndex, e);
      }
      return sagaId;
    } finally {
      crashAfterStepIndex.remove();
      capturedSagaId.remove();
    }
  }
}
