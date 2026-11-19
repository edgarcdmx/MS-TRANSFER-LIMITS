package com.banamex.transferlimits.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * All channel limits of an account with their consumption on a business date.
 *
 * @param accountId account identifier
 * @param businessDate business date used to compute the consumption
 * @param channels usage per configured channel, ordered by {@link Channel} declaration order
 */
public record AccountLimits(String accountId, LocalDate businessDate, List<ChannelUsage> channels) {

  /** Validates the aggregate and makes the channel list immutable. */
  public AccountLimits {
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(businessDate, "businessDate");
    channels = List.copyOf(channels);
  }
}
