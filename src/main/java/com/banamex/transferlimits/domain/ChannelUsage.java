package com.banamex.transferlimits.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A channel limit together with what was consumed on the current business date.
 *
 * @param limit configured limit
 * @param consumedToday amount approved on the business date (scale 2, never negative)
 */
public record ChannelUsage(ChannelLimit limit, BigDecimal consumedToday) {

  /** Validates and normalizes the usage. */
  public ChannelUsage {
    Objects.requireNonNull(limit, "limit");
    Objects.requireNonNull(consumedToday, "consumedToday");
    if (consumedToday.signum() < 0) {
      throw new IllegalArgumentException("consumedToday must not be negative");
    }
    consumedToday = Amounts.normalize(consumedToday);
  }

  /**
   * Remaining daily amount.
   *
   * @return {@code max(0, dailyLimit - consumedToday)} with scale 2
   */
  public BigDecimal availableToday() {
    return limit.dailyLimit().subtract(consumedToday).max(Amounts.ZERO);
  }
}
