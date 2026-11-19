package com.banamex.transferlimits.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Helpers for monetary amounts: scale 2, never rounded silently. */
public final class Amounts {

  /** Scale used for every monetary amount (cents). */
  public static final int SCALE = 2;

  /** Zero with scale 2. */
  public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

  /** Default and only supported currency (ISO 4217). */
  public static final String DEFAULT_CURRENCY = "MXN";

  private Amounts() {}

  /**
   * Returns the amount with scale 2.
   *
   * @param amount amount to normalize, not null
   * @return the same value with scale 2
   * @throws ArithmeticException if the amount has more than 2 significant decimals
   */
  public static BigDecimal normalize(BigDecimal amount) {
    return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
  }

  /**
   * Tells whether the amount can be represented with scale 2 without rounding.
   *
   * @param amount amount to check, not null
   * @return {@code true} if it has at most 2 significant decimals
   */
  public static boolean hasValidScale(BigDecimal amount) {
    return amount.stripTrailingZeros().scale() <= SCALE;
  }
}
