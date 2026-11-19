package com.banamex.transferlimits.domain.exception;

import com.banamex.transferlimits.domain.Channel;
import java.math.BigDecimal;

/**
 * A channel limit violates its invariants, typically {@code perOperationLimit > dailyLimit} (HTTP
 * 422).
 */
public class InvalidLimitConfigurationException extends DomainException {

  /** Error code of this exception. */
  public static final String CODE = "INVALID_LIMIT_CONFIGURATION";

  private static final long serialVersionUID = 1L;

  private final String accountId;
  private final Channel channel;
  private final BigDecimal dailyLimit;
  private final BigDecimal perOperationLimit;

  /**
   * Creates the exception.
   *
   * @param message technical description of the violated invariant
   * @param accountId account identifier
   * @param channel channel being configured
   * @param dailyLimit requested daily limit (may be null)
   * @param perOperationLimit requested per-operation limit (may be null)
   */
  public InvalidLimitConfigurationException(
      String message,
      String accountId,
      Channel channel,
      BigDecimal dailyLimit,
      BigDecimal perOperationLimit) {
    super(message);
    this.accountId = accountId;
    this.channel = channel;
    this.dailyLimit = dailyLimit;
    this.perOperationLimit = perOperationLimit;
  }

  @Override
  public String code() {
    return CODE;
  }

  /**
   * Account identifier.
   *
   * @return the account identifier
   */
  public String accountId() {
    return accountId;
  }

  /**
   * Channel being configured.
   *
   * @return the channel
   */
  public Channel channel() {
    return channel;
  }

  /**
   * Requested daily limit.
   *
   * @return the daily limit, possibly null
   */
  public BigDecimal dailyLimit() {
    return dailyLimit;
  }

  /**
   * Requested per-operation limit.
   *
   * @return the per-operation limit, possibly null
   */
  public BigDecimal perOperationLimit() {
    return perOperationLimit;
  }
}
