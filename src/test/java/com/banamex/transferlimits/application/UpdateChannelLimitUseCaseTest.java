package com.banamex.transferlimits.application;

import static com.banamex.transferlimits.application.TestFixtures.ACCOUNT;
import static com.banamex.transferlimits.application.TestFixtures.BUSINESS_DATE;
import static com.banamex.transferlimits.application.TestFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.ChannelUsage;
import com.banamex.transferlimits.domain.exception.InvalidLimitConfigurationException;
import com.banamex.transferlimits.domain.port.ConsumptionRepository;
import com.banamex.transferlimits.domain.port.LimitRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UpdateChannelLimitUseCaseTest {

  private LimitRepository limitRepository;
  private ConsumptionRepository consumptionRepository;
  private UpdateChannelLimitUseCase useCase;

  @BeforeEach
  void setUp() {
    limitRepository = mock(LimitRepository.class);
    consumptionRepository = mock(ConsumptionRepository.class);
    when(limitRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    useCase =
        new UpdateChannelLimitUseCase(
            limitRepository, consumptionRepository, TestFixtures.fixedDateProvider());
  }

  @Test
  void savesNormalizedLimitAndReturnsItWithTodaysConsumption() {
    when(consumptionRepository.consumedOn(ACCOUNT, Channel.WEB, BUSINESS_DATE))
        .thenReturn(new BigDecimal("700.00"));

    ChannelUsage result =
        useCase.execute(
            ACCOUNT, Channel.WEB, new BigDecimal("5000"), new BigDecimal("1000.5"), null);

    ArgumentCaptor<ChannelLimit> saved = ArgumentCaptor.forClass(ChannelLimit.class);
    verify(limitRepository).save(saved.capture());
    assertThat(saved.getValue().accountId()).isEqualTo(ACCOUNT);
    assertThat(saved.getValue().channel()).isEqualTo(Channel.WEB);
    assertThat(saved.getValue().dailyLimit()).hasToString("5000.00");
    assertThat(saved.getValue().perOperationLimit()).hasToString("1000.50");
    assertThat(saved.getValue().currency()).isEqualTo("MXN");
    assertThat(saved.getValue().updatedAt()).isEqualTo(NOW);
    assertThat(result.limit()).isEqualTo(saved.getValue());
    assertThat(result.consumedToday()).hasToString("700.00");
    assertThat(result.availableToday()).hasToString("4300.00");
  }

  @Test
  void loweringTheDailyLimitBelowConsumptionLeavesNothingAvailable() {
    when(consumptionRepository.consumedOn(ACCOUNT, Channel.BRANCH, BUSINESS_DATE))
        .thenReturn(new BigDecimal("3000.00"));

    ChannelUsage result =
        useCase.execute(
            ACCOUNT, Channel.BRANCH, new BigDecimal("1000"), new BigDecimal("500"), "MXN");

    assertThat(result.availableToday()).hasToString("0.00");
  }

  @Test
  void returnsWhatTheRepositoryStored() {
    ChannelLimit stored =
        new ChannelLimit(
            ACCOUNT, Channel.WEB, new BigDecimal("10"), new BigDecimal("1"), "MXN", NOW);
    when(limitRepository.save(any())).thenReturn(stored);
    when(consumptionRepository.consumedOn(any(), any(), any())).thenReturn(BigDecimal.ZERO);

    ChannelUsage result =
        useCase.execute(ACCOUNT, Channel.WEB, new BigDecimal("10"), new BigDecimal("1"), "MXN");

    assertThat(result.limit()).isSameAs(stored);
  }

  @Test
  void invalidConfigurationIsNotSaved() {
    assertThatThrownBy(() ->
            useCase.execute(
                ACCOUNT, Channel.WEB, new BigDecimal("100"), new BigDecimal("100.01"), "MXN"))
        .isInstanceOf(InvalidLimitConfigurationException.class);

    verify(limitRepository, never()).save(any());
    verifyNoInteractions(consumptionRepository);
  }
}
