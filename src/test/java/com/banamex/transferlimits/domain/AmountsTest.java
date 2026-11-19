package com.banamex.transferlimits.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AmountsTest {

  @Test
  void zeroHasScaleTwo() {
    assertThat(Amounts.ZERO).isEqualByComparingTo("0").hasToString("0.00");
    assertThat(Amounts.ZERO.scale()).isEqualTo(Amounts.SCALE);
    assertThat(Amounts.DEFAULT_CURRENCY).isEqualTo("MXN");
  }

  @ParameterizedTest
  @CsvSource({
    "10, 10.00",
    "10.5, 10.50",
    "10.25, 10.25",
    "1.100, 1.10",
    "0, 0.00",
    "1E+3, 1000.00"
  })
  void normalizeSetsScaleTwoWithoutRounding(String input, String expected) {
    BigDecimal normalized = Amounts.normalize(new BigDecimal(input));

    assertThat(normalized).hasToString(expected);
    assertThat(normalized.scale()).isEqualTo(2);
  }

  @Test
  void normalizeRejectsMoreThanTwoSignificantDecimals() {
    assertThatThrownBy(() -> Amounts.normalize(new BigDecimal("1.001")))
        .isInstanceOf(ArithmeticException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "10, true",
    "10.1, true",
    "10.12, true",
    "10.120, true",
    "10.1200000, true",
    "1E+3, true",
    "10.123, false",
    "0.001, false"
  })
  void hasValidScale(String input, boolean expected) {
    assertThat(Amounts.hasValidScale(new BigDecimal(input))).isEqualTo(expected);
  }
}
