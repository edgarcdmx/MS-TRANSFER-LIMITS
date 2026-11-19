package com.banamex.transferlimits.domain.exception;

import com.banamex.transferlimits.domain.Channel;

/** The account has no limit configured for the requested channel (HTTP 404). */
public class LimitNotFoundException extends DomainException {

  /** Error code of this exception. */
  public static final String CODE = "LIMIT_NOT_FOUND";

  private static final long serialVersionUID = 1L;

  private final String accountId;
  private final Channel channel;

  /**
   * Creates the exception.
   *
   * @param accountId account identifier
   * @param channel channel without a configured limit
   */
  public LimitNotFoundException(String accountId, Channel channel) {
    super("Account " + accountId + " has no limit configured for channel " + channel);
    this.accountId = accountId;
    this.channel = channel;
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
   * Channel without a configured limit.
   *
   * @return the channel
   */
  public Channel channel() {
    return channel;
  }
}
