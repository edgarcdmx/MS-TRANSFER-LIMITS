package com.banamex.transferlimits.domain.port;

import com.banamex.transferlimits.domain.Channel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Output port for the daily consumption of an account and channel. */
public interface ConsumptionRepository {

  /**
   * Amount consumed on a business date.
   *
   * @param accountId account identifier
   * @param channel channel
   * @param businessDate business date
   * @return consumed amount with scale 2; zero if nothing was consumed
   */
  BigDecimal consumedOn(String accountId, Channel channel, LocalDate businessDate);

  /**
   * Atomically adds {@code amount} to the consumption of the business date if, and only if, the
   * result does not exceed {@code dailyLimit}.
   *
   * <p>Implementations MUST be atomic with respect to concurrent callers: the check and the
   * increment happen as a single indivisible operation, so the consumption can never exceed the
   * limit. It must run inside the caller's transaction so a later rollback undoes it.
   *
   * @param accountId account identifier
   * @param channel channel
   * @param businessDate business date
   * @param amount amount to consume (positive)
   * @param dailyLimit daily limit that must not be exceeded
   * @return the new consumed amount, or empty if consuming would exceed the limit (nothing changed)
   */
  Optional<BigDecimal> tryConsume(
      String accountId,
      Channel channel,
      LocalDate businessDate,
      BigDecimal amount,
      BigDecimal dailyLimit);
}
