package com.banamex.transferlimits.application;

import static com.banamex.transferlimits.application.TestFixtures.ACCOUNT;
import static com.banamex.transferlimits.application.TestFixtures.BUSINESS_DATE;
import static com.banamex.transferlimits.application.TestFixtures.limit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.banamex.transferlimits.domain.AccountLimits;
import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelUsage;
import com.banamex.transferlimits.domain.exception.AccountLimitsNotFoundException;
import com.banamex.transferlimits.domain.port.ConsumptionRepository;
import com.banamex.transferlimits.domain.port.LimitRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GetAccountLimitsUseCaseTest {

  private LimitRepository limitRepository;
  private ConsumptionRepository consumptionRepository;
  private GetAccountLimitsUseCase useCase;

  @BeforeEach
  void setUp() {
    limitRepository = mock(LimitRepository.class);
    consumptionRepository = mock(ConsumptionRepository.class);
    useCase =
        new GetAccountLimitsUseCase(
            limitRepository, consumptionRepository, TestFixtures.fixedDateProvider());
  }

  @Test
  void throwsWhenAccountHasNoLimits() {
    when(limitRepository.findByAccount(ACCOUNT)).thenReturn(List.of());

    assertThatThrownBy(() -> useCase.execute(ACCOUNT))
        .isInstanceOfSatisfying(
            AccountLimitsNotFoundException.class,
            exception -> assertThat(exception.accountId()).isEqualTo(ACCOUNT));
    verifyNoInteractions(consumptionRepository);
  }

  @Test
  void returnsUsageOfEveryChannelOrderedByChannelWithTodaysConsumption() {
    when(limitRepository.findByAccount(ACCOUNT))
        .thenReturn(
            List.of(
                limit(Channel.BRANCH, "20000", "10000"),
                limit(Channel.MOBILE_APP, "5000", "1000"),
                limit(Channel.WEB, "8000", "2000")));
    when(consumptionRepository.consumedOn(ACCOUNT, Channel.MOBILE_APP, BUSINESS_DATE))
        .thenReturn(new BigDecimal("1500.00"));
    when(consumptionRepository.consumedOn(ACCOUNT, Channel.WEB, BUSINESS_DATE))
        .thenReturn(new BigDecimal("9000.00"));
    when(consumptionRepository.consumedOn(ACCOUNT, Channel.BRANCH, BUSINESS_DATE))
        .thenReturn(new BigDecimal("0.00"));

    AccountLimits result = useCase.execute(ACCOUNT);

    assertThat(result.accountId()).isEqualTo(ACCOUNT);
    assertThat(result.businessDate()).isEqualTo(BUSINESS_DATE);
    assertThat(result.channels())
        .extracting(usage -> usage.limit().channel())
        .containsExactly(Channel.MOBILE_APP, Channel.WEB, Channel.BRANCH);
    assertThat(result.channels())
        .extracting(ChannelUsage::consumedToday)
        .extracting(BigDecimal::toPlainString)
        .containsExactly("1500.00", "9000.00", "0.00");
    assertThat(result.channels())
        .extracting(ChannelUsage::availableToday)
        .extracting(BigDecimal::toPlainString)
        .containsExactly("3500.00", "0.00", "20000.00");
  }

  @Test
  void usesTheBusinessDateOfTheProvider() {
    when(limitRepository.findByAccount(ACCOUNT))
        .thenReturn(List.of(limit(Channel.WEB, "100", "10")));
    when(consumptionRepository.consumedOn(any(), any(), any())).thenReturn(BigDecimal.ZERO);

    AccountLimits result = useCase.execute(ACCOUNT);

    assertThat(result.businessDate()).isEqualTo(BUSINESS_DATE);
    assertThat(result.channels())
        .singleElement()
        .satisfies(usage -> assertThat(usage.consumedToday()).hasToString("0.00"));
  }
}
