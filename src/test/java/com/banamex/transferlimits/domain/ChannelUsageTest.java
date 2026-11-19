package com.banamex.transferlimits.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChannelUsageTest {

  private static final ChannelLimit LIMIT =
      new ChannelLimit(
          "ACC-001",
          Channel.WEB,
          new BigDecimal("5000"),
          new BigDecimal("1000"),
          "MXN",
          Instant.parse("2026-11-19T18:00:00Z"));

  @ParameterizedTest
  @CsvSource({
    "0, 5000.00",
    "1250.5, 3749.50",
    "4999.99, 0.01",
    "5000, 0.00",
    "5000.01, 0.00",
    "7000, 0.00"
  })
  void availableTodayIsNeverNegative(String consumed, String expectedAvailable) {
    ChannelUsage usage = new ChannelUsage(LIMIT, new BigDecimal(consumed));

    assertThat(usage.availableToday()).hasToString(expectedAvailable);
  }

  @Test
  void normalizesConsumedToScaleTwo() {
    ChannelUsage usage = new ChannelUsage(LIMIT, BigDecimal.valueOf(15));

    assertThat(usage.consumedToday()).hasToString("15.00");
    assertThat(usage.limit()).isSameAs(LIMIT);
  }

  @Test
  void rejectsNullArguments() {
    assertThatThrownBy(() -> new ChannelUsage(null, BigDecimal.ONE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("limit");
    assertThatThrownBy(() -> new ChannelUsage(LIMIT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("consumedToday");
  }

  @Test
  void rejectsNegativeConsumption() {
    assertThatThrownBy(() -> new ChannelUsage(LIMIT, new BigDecimal("-0.01")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  void rejectsConsumptionWithMoreThanTwoDecimals() {
    assertThatThrownBy(() -> new ChannelUsage(LIMIT, new BigDecimal("1.005")))
        .isInstanceOf(ArithmeticException.class);
  }
}
