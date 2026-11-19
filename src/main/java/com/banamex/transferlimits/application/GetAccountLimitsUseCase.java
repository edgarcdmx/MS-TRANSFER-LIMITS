package com.banamex.transferlimits.application;

import com.banamex.transferlimits.domain.AccountLimits;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.ChannelUsage;
import com.banamex.transferlimits.domain.exception.AccountLimitsNotFoundException;
import com.banamex.transferlimits.domain.port.BusinessDateProvider;
import com.banamex.transferlimits.domain.port.ConsumptionRepository;
import com.banamex.transferlimits.domain.port.LimitRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Returns the limits of an account and their consumption on the current business date. */
@Service
public class GetAccountLimitsUseCase {

  private final LimitRepository limitRepository;
  private final ConsumptionRepository consumptionRepository;
  private final BusinessDateProvider businessDateProvider;

  /**
   * Creates the use case.
   *
   * @param limitRepository channel limits store
   * @param consumptionRepository daily consumption store
   * @param businessDateProvider business date source
   */
  public GetAccountLimitsUseCase(
      LimitRepository limitRepository,
      ConsumptionRepository consumptionRepository,
      BusinessDateProvider businessDateProvider) {
    this.limitRepository = limitRepository;
    this.consumptionRepository = consumptionRepository;
    this.businessDateProvider = businessDateProvider;
  }

  /**
   * Loads every configured channel of the account with today's consumption.
   *
   * @param accountId account identifier
   * @return limits and usage ordered by channel
   * @throws AccountLimitsNotFoundException if the account has no limit configured
   */
  @Transactional(readOnly = true)
  public AccountLimits execute(String accountId) {
    List<ChannelLimit> limits = limitRepository.findByAccount(accountId);
    if (limits.isEmpty()) {
      throw new AccountLimitsNotFoundException(accountId);
    }
    LocalDate businessDate = businessDateProvider.today();
    List<ChannelUsage> usages =
        limits.stream()
            .sorted(Comparator.comparing(ChannelLimit::channel))
            .map(limit ->
                new ChannelUsage(
                    limit,
                    consumptionRepository.consumedOn(accountId, limit.channel(), businessDate)))
            .toList();
    return new AccountLimits(accountId, businessDate, usages);
  }
}
