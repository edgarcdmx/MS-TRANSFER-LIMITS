package com.banamex.transferlimits.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.banamex.transferlimits.domain.exception.InvalidLimitConfigurationException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChannelLimitTest {

  private static final String ACCOUNT = "ACC-001";
  private static final Instant NOW = Instant.parse("2026-11-19T18:00:00Z");

  private static ChannelLimit limit(String daily, String perOperation) {
    return new ChannelLimit(
        ACCOUNT,
        Channel.WEB,
        daily == null ? null : new BigDecimal(daily),
        perOperation == null ? null : new BigDecimal(perOperation),
        "MXN",
        NOW);
  }

  @Test
  void createsValidLimitAndNormalizesAmounts() {
    ChannelLimit limit =
        new ChannelLimit(
            ACCOUNT,
            Channel.MOBILE_APP,
            BigDecimal.valueOf(5000),
            new BigDecimal("1000.5"),
            "MXN",
            NOW);

    assertThat(limit.accountId()).isEqualTo(ACCOUNT);
    assertThat(limit.channel()).isEqualTo(Channel.MOBILE_APP);
    assertThat(limit.dailyLimit()).hasToString("5000.00");
    assertThat(limit.perOperationLimit()).hasToString("1000.50");
    assertThat(limit.currency()).isEqualTo("MXN");
    assertThat(limit.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void trailingZerosBeyondScaleAreAccepted() {
    ChannelLimit limit = limit("100.000", "50.0000");

    assertThat(limit.dailyLimit()).hasToString("100.00");
    assertThat(limit.perOperationLimit()).hasToString("50.00");
  }

  @Test
  void nullCurrencyDefaultsToMxn() {
    ChannelLimit limit =
        new ChannelLimit(ACCOUNT, Channel.BRANCH, BigDecimal.TEN, BigDecimal.ONE, null, NOW);

    assertThat(limit.currency()).isEqualTo(Amounts.DEFAULT_CURRENCY);
  }

  @Test
  void explicitCurrencyIsKept() {
    ChannelLimit limit =
        new ChannelLimit(ACCOUNT, Channel.BRANCH, BigDecimal.TEN, BigDecimal.ONE, "USD", NOW);

    assertThat(limit.currency()).isEqualTo("USD");
  }

  @Test
  void perOperationEqualToDailyIsValid() {
    ChannelLimit limit = limit("1000", "1000.00");

    assertThat(limit.perOperationLimit()).isEqualByComparingTo(limit.dailyLimit());
  }

  @Test
  void zeroLimitsAreValid() {
    ChannelLimit limit = limit("0", "0");

    assertThat(limit.dailyLimit()).isEqualTo(Amounts.ZERO);
    assertThat(limit.perOperationLimit()).isEqualTo(Amounts.ZERO);
  }

  @Test
  void requiresAccountChannelAndUpdatedAt() {
    assertThatThrownBy(
            () -> new ChannelLimit(null, Channel.WEB, BigDecimal.TEN, BigDecimal.ONE, null, NOW))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("accountId");
    assertThatThrownBy(
            () -> new ChannelLimit(ACCOUNT, null, BigDecimal.TEN, BigDecimal.ONE, null, NOW))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("channel");
    assertThatThrownBy(() ->
            new ChannelLimit(ACCOUNT, Channel.WEB, BigDecimal.TEN, BigDecimal.ONE, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("updatedAt");
  }

  @ParameterizedTest
  @CsvSource(
      value = {"null, 10", "10, null", "null, null"},
      nullValues = "null")
  void requiresBothLimits(String daily, String perOperation) {
    assertThatThrownBy(() -> limit(daily, perOperation))
        .isInstanceOfSatisfying(
            InvalidLimitConfigurationException.class,
            exception -> {
              assertThat(exception).hasMessageContaining("required");
              assertThat(exception.accountId()).isEqualTo(ACCOUNT);
              assertThat(exception.channel()).isEqualTo(Channel.WEB);
            });
  }

  @ParameterizedTest
  @CsvSource({"-0.01, 0", "100, -1", "-5, -10"})
  void rejectsNegativeLimits(String daily, String perOperation) {
    assertThatThrownBy(() -> limit(daily, perOperation))
        .isInstanceOf(InvalidLimitConfigurationException.class)
        .hasMessageContaining("negative");
  }

  @ParameterizedTest
  @CsvSource({"100.001, 10", "100, 10.001", "100.123, 10.456"})
  void rejectsMoreThanTwoDecimals(String daily, String perOperation) {
    assertThatThrownBy(() -> limit(daily, perOperation))
        .isInstanceOf(InvalidLimitConfigurationException.class)
        .hasMessageContaining("2 decimals");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1000.01", "1001", "999999"})
  void rejectsPerOperationGreaterThanDaily(String perOperation) {
    assertThatThrownBy(() -> limit("1000", perOperation))
        .isInstanceOfSatisfying(
            InvalidLimitConfigurationException.class,
            exception -> {
              assertThat(exception)
                  .hasMessage("perOperationLimit must be less than or equal to dailyLimit");
              assertThat(exception.dailyLimit()).hasToString("1000.00");
              assertThat(exception.perOperationLimit())
                  .isEqualByComparingTo(new BigDecimal(perOperation));
            });
  }

  @ParameterizedTest
  @NullSource
  void exceptionKeepsNullLimits(String daily) {
    assertThatThrownBy(() -> limit(daily, "1"))
        .isInstanceOfSatisfying(
            InvalidLimitConfigurationException.class,
            exception -> {
              assertThat(exception.dailyLimit()).isNull();
              assertThat(exception.perOperationLimit()).isEqualByComparingTo("1");
            });
  }
}
