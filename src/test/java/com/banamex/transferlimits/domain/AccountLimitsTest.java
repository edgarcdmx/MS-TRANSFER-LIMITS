package com.banamex.transferlimits.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountLimitsTest {

  private static final LocalDate DATE = LocalDate.of(2026, 11, 19);

  private static ChannelUsage usage(Channel channel) {
    return new ChannelUsage(
        new ChannelLimit(
            "ACC-001",
            channel,
            BigDecimal.TEN,
            BigDecimal.ONE,
            "MXN",
            Instant.parse("2026-11-19T18:00:00Z")),
        BigDecimal.ZERO);
  }

  @Test
  void keepsGivenOrderAndValues() {
    List<ChannelUsage> usages =
        List.of(usage(Channel.MOBILE_APP), usage(Channel.WEB), usage(Channel.BRANCH));

    AccountLimits limits = new AccountLimits("ACC-001", DATE, usages);

    assertThat(limits.accountId()).isEqualTo("ACC-001");
    assertThat(limits.businessDate()).isEqualTo(DATE);
    assertThat(limits.channels())
        .extracting(channelUsage -> channelUsage.limit().channel())
        .containsExactly(Channel.MOBILE_APP, Channel.WEB, Channel.BRANCH);
  }

  @Test
  void channelsAreDefensiveImmutableCopy() {
    List<ChannelUsage> usages = new ArrayList<>();
    usages.add(usage(Channel.WEB));

    AccountLimits limits = new AccountLimits("ACC-001", DATE, usages);
    usages.add(usage(Channel.BRANCH));

    assertThat(limits.channels()).hasSize(1);
    assertThatThrownBy(() -> limits.channels().add(usage(Channel.BRANCH)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsNullArguments() {
    List<ChannelUsage> usages = List.of();
    assertThatThrownBy(() -> new AccountLimits(null, DATE, usages))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("accountId");
    assertThatThrownBy(() -> new AccountLimits("ACC-001", null, usages))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("businessDate");
    assertThatThrownBy(() -> new AccountLimits("ACC-001", DATE, null))
        .isInstanceOf(NullPointerException.class);
  }
}
