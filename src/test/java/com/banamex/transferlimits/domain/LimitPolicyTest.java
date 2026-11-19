package com.banamex.transferlimits.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LimitPolicyTest {

  private static final ChannelLimit LIMIT =
      new ChannelLimit(
          "ACC-001",
          Channel.MOBILE_APP,
          new BigDecimal("5000.00"),
          new BigDecimal("1000.00"),
          "MXN",
          Instant.parse("2026-11-19T18:00:00Z"));

  @ParameterizedTest(name = "amount {0} -> rejected={1}")
  @CsvSource({
    "0.01, false",
    "999.99, false",
    "1000.00, false",
    "1000, false",
    "1000.01, true",
    "1001, true",
    "5000.00, true"
  })
  void perOperationBoundary(String amount, boolean rejected) {
    Optional<RejectionReason> result = LimitPolicy.checkPerOperation(LIMIT, new BigDecimal(amount));

    if (rejected) {
      assertThat(result).contains(RejectionReason.PER_OPERATION_LIMIT_EXCEEDED);
    } else {
      assertThat(result).isEmpty();
    }
  }

  @ParameterizedTest(name = "consumed {0} + amount {1} -> rejected={2}")
  @CsvSource({
    "0.00, 0.01, false",
    "0.00, 5000.00, false",
    "4000.00, 999.99, false",
    "4000.00, 1000.00, false",
    "4000.00, 1000.01, true",
    "4999.99, 0.01, false",
    "4999.99, 0.02, true",
    "5000.00, 0.01, true",
    "0.00, 5000.01, true",
    "6000.00, 0.01, true"
  })
  void dailyBoundary(String consumed, String amount, boolean rejected) {
    Optional<RejectionReason> result =
        LimitPolicy.checkDaily(LIMIT, new BigDecimal(consumed), new BigDecimal(amount));

    if (rejected) {
      assertThat(result).contains(RejectionReason.DAILY_LIMIT_EXCEEDED);
    } else {
      assertThat(result).isEmpty();
    }
  }

  @ParameterizedTest
  @CsvSource({"0, 0", "-1, 0", "0.001, 0"})
  void policyDoesNotRejectZeroNegativeOrUnscaledAmountsThemselves(String amount, String consumed) {
    // Amount sanity (> 0, scale <= 2) is enforced by TransferRequest, not by the policy.
    BigDecimal value = new BigDecimal(amount);

    assertThat(LimitPolicy.checkPerOperation(LIMIT, value)).isEmpty();
    assertThat(LimitPolicy.checkDaily(LIMIT, new BigDecimal(consumed), value)).isEmpty();
  }
}
