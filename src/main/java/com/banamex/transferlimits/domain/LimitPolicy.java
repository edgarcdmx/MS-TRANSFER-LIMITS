package com.banamex.transferlimits.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Pure business rules for approving a transfer.
 *
 * <p>The daily rule is also enforced atomically in the database (see ADR-002), which is the final
 * authority under concurrency; {@link #checkDaily} is its in-memory equivalent, useful to explain
 * rejections and for test doubles of {@code ConsumptionRepository}.
 */
public final class LimitPolicy {

  private LimitPolicy() {}

  /**
   * Checks the per-operation limit.
   *
   * @param limit channel limit
   * @param amount transfer amount
   * @return {@link RejectionReason#PER_OPERATION_LIMIT_EXCEEDED} if {@code amount >
   *     perOperationLimit}, otherwise empty
   */
  public static Optional<RejectionReason> checkPerOperation(ChannelLimit limit, BigDecimal amount) {
    return amount.compareTo(limit.perOperationLimit()) > 0
        ? Optional.of(RejectionReason.PER_OPERATION_LIMIT_EXCEEDED)
        : Optional.empty();
  }

  /**
   * Checks the daily limit.
   *
   * @param limit channel limit
   * @param consumedToday amount already consumed on the business date
   * @param amount transfer amount
   * @return {@link RejectionReason#DAILY_LIMIT_EXCEEDED} if {@code consumedToday + amount >
   *     dailyLimit}, otherwise empty
   */
  public static Optional<RejectionReason> checkDaily(
      ChannelLimit limit, BigDecimal consumedToday, BigDecimal amount) {
    return consumedToday.add(amount).compareTo(limit.dailyLimit()) > 0
        ? Optional.of(RejectionReason.DAILY_LIMIT_EXCEEDED)
        : Optional.empty();
  }
}
