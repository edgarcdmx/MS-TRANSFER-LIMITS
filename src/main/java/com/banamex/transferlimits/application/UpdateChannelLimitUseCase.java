package com.banamex.transferlimits.application;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.ChannelUsage;
import com.banamex.transferlimits.domain.exception.InvalidLimitConfigurationException;
import com.banamex.transferlimits.domain.port.BusinessDateProvider;
import com.banamex.transferlimits.domain.port.ConsumptionRepository;
import com.banamex.transferlimits.domain.port.LimitRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates or updates (upsert) the limits of an account channel. */
@Service
public class UpdateChannelLimitUseCase {

  private final LimitRepository limitRepository;
  private final ConsumptionRepository consumptionRepository;
  private final BusinessDateProvider businessDateProvider;

  /**
   * Creates the use case.
   *
   * @param limitRepository channel limits store
   * @param consumptionRepository daily consumption store
   * @param businessDateProvider clock and business date source
   */
  public UpdateChannelLimitUseCase(
      LimitRepository limitRepository,
      ConsumptionRepository consumptionRepository,
      BusinessDateProvider businessDateProvider) {
    this.limitRepository = limitRepository;
    this.consumptionRepository = consumptionRepository;
    this.businessDateProvider = businessDateProvider;
  }

  /**
   * Stores the limits of the channel and returns them with today's consumption.
   *
   * <p>Lowering the daily limit below what was already consumed today is allowed: the available
   * amount becomes zero and further transfers are rejected.
   *
   * @param accountId account identifier
   * @param channel channel to configure
   * @param dailyLimit daily limit (non-negative, at most 2 decimals)
   * @param perOperationLimit per-operation limit (non-negative, at most 2 decimals, {@code <=
   *     dailyLimit})
   * @param currency ISO 4217 currency; null means MXN
   * @return the stored limit and today's consumption
   * @throws InvalidLimitConfigurationException if the limits violate an invariant
   */
  @Transactional
  public ChannelUsage execute(
      String accountId,
      Channel channel,
      BigDecimal dailyLimit,
      BigDecimal perOperationLimit,
      String currency) {
    Instant now = businessDateProvider.now();
    ChannelLimit saved =
        limitRepository.save(
            new ChannelLimit(accountId, channel, dailyLimit, perOperationLimit, currency, now));
    return new ChannelUsage(
        saved,
        consumptionRepository.consumedOn(
            accountId, channel, businessDateProvider.businessDateOf(now)));
  }
}
