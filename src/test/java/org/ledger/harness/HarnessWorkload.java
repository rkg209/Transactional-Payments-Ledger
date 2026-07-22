package org.ledger.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * SPEC 0007 — builds the storm's request plan: a deterministic (seeded) mix of fixed floor-account
 * legs and random hot-pair legs, ~30% of which are flagged to get a byte-identical duplicate twin
 * fired concurrently under the same {@code Idempotency-Key}. Pure and Spring/Postgres-free so it is
 * independently unit-testable.
 */
public final class HarnessWorkload {

  /** One (from, to, amount) leg before an idempotency key or duplicate flag is assigned. */
  public record Leg(UUID fromId, UUID toId, long amountMinor) {}

  /** One logical transfer the storm will fire — once, or twice if {@code duplicate}. */
  public record PlannedTransfer(
      int logicalIndex,
      UUID fromId,
      UUID toId,
      long amountMinor,
      String idempotencyKey,
      boolean duplicate) {}

  private HarnessWorkload() {}

  /**
   * {@code fixedLegs} (the floor-account draws) are included verbatim; {@code randomLogicalCount}
   * additional legs are drawn from {@code hotPairs} with a uniformly random amount in {@code
   * [minAmount, maxAmount]}. The combined list is shuffled under {@code random} before duplicate
   * flags and keys are assigned, so fixed and random legs interleave the same way a real storm
   * would.
   */
  public static List<PlannedTransfer> buildPlan(
      Random random,
      List<UUID[]> hotPairs,
      List<Leg> fixedLegs,
      int randomLogicalCount,
      long minAmount,
      long maxAmount,
      double duplicateFraction,
      String keyPrefix) {
    List<Leg> legs = new ArrayList<>(fixedLegs);
    long amountSpan = maxAmount - minAmount + 1;
    for (int i = 0; i < randomLogicalCount; i++) {
      UUID[] pair = hotPairs.get(random.nextInt(hotPairs.size()));
      long amount = minAmount + (long) random.nextInt((int) amountSpan);
      legs.add(new Leg(pair[0], pair[1], amount));
    }
    Collections.shuffle(legs, random);

    List<PlannedTransfer> plan = new ArrayList<>(legs.size());
    for (int i = 0; i < legs.size(); i++) {
      Leg leg = legs.get(i);
      boolean duplicate = random.nextDouble() < duplicateFraction;
      String key = keyPrefix + i + "-" + UUID.randomUUID();
      plan.add(new PlannedTransfer(i, leg.fromId(), leg.toId(), leg.amountMinor(), key, duplicate));
    }
    return plan;
  }
}
