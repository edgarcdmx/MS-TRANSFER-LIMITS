package com.banamex.transferlimits.domain;

import com.banamex.transferlimits.domain.exception.InvalidLimitConfigurationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Daily and per-operation limits configured for an account and channel.
 *
 * <p>Invariants: both limits are present, non-negative, have at most 2 decimals (normalized to
 * scale 2) and {@code perOperationLimit <= dailyLimit}. A violation raises {@link
 * InvalidLimitConfigurationException}. A null currency defaults to {@link
 * Amounts#DEFAULT_CURRENCY}.
 *
 * @param accountId account identifier
 * @param channel channel the limit applies to
 * @param dailyLimit maximum amount that can be consumed per business date
 * @param perOperationLimit maximum amount of a single transfer
 * @param currency ISO 4217 currency code
 * @param updatedAt last modification instant
 */
public record ChannelLimit(
    String accountId,
    Channel channel,
    BigDecimal dailyLimit,
    BigDecimal perOperationLimit,
    String currency,
    Instant updatedAt) {

  /**
   * Validates and normalizes the limit.
   *
   * @throws InvalidLimitConfigurationException if an amount invariant is violated
   */
  public ChannelLimit {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (dailyLimit == null || perOperationLimit == null) {
      throw new InvalidLimitConfigurationException(
          "dailyLimit and perOperationLimit are required",
          accountId,
          channel,
          dailyLimit,
          perOperationLimit);
    }
    if (dailyLimit.signum() < 0 || perOperationLimit.signum() < 0) {
      throw new InvalidLimitConfigurationException(
          "Limits must not be negative", accountId, channel, dailyLimit, perOperationLimit);
    }
    if (!Amounts.hasValidScale(dailyLimit) || !Amounts.hasValidScale(perOperationLimit)) {
      throw new InvalidLimitConfigurationException(
          "Limits must have at most 2 decimals", accountId, channel, dailyLimit, perOperationLimit);
    }
    dailyLimit = Amounts.normalize(dailyLimit);
    perOperationLimit = Amounts.normalize(perOperationLimit);
    if (perOperationLimit.compareTo(dailyLimit) > 0) {
      throw new InvalidLimitConfigurationException(
          "perOperationLimit must be less than or equal to dailyLimit",
          accountId,
          channel,
          dailyLimit,
          perOperationLimit);
    }
    currency = currency == null ? Amounts.DEFAULT_CURRENCY : currency;
  }
}
