package com.banamex.transferlimits.infrastructure.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SystemBusinessDateProviderTest {

  private static final ZoneId MEXICO_CITY = ZoneId.of("America/Mexico_City");

  private static SystemBusinessDateProvider providerAt(String instant) {
    return new SystemBusinessDateProvider(
        Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), MEXICO_CITY);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "2026-11-19T05:59:59.999999Z, 2026-11-18",
    "2026-11-19T06:00:00Z, 2026-11-19",
    "2026-11-19T00:30:00Z, 2026-11-18",
    "2026-11-20T05:59:59Z, 2026-11-19",
    "2026-07-01T05:59:59Z, 2026-06-30",
    "2026-07-01T06:00:00Z, 2026-07-01",
    "2027-01-01T05:59:59Z, 2026-12-31",
    "2027-01-01T06:00:00Z, 2027-01-01"
  })
  void todayIsTheDateInMexicoCityAroundMidnight(String instant, String expectedDate) {
    SystemBusinessDateProvider provider = providerAt(instant);

    assertThat(provider.today()).isEqualTo(LocalDate.parse(expectedDate));
    assertThat(provider.businessDateOf(Instant.parse(instant)))
        .isEqualTo(LocalDate.parse(expectedDate));
  }

  @Test
  void nowIsTruncatedToMicroseconds() {
    SystemBusinessDateProvider provider = providerAt("2026-11-19T18:00:00.123456789Z");

    assertThat(provider.now()).isEqualTo(Instant.parse("2026-11-19T18:00:00.123456Z"));
  }

  @Test
  void nowKeepsInstantsAlreadyInMicroseconds() {
    SystemBusinessDateProvider provider = providerAt("2026-11-19T18:00:00.000001Z");

    assertThat(provider.now()).isEqualTo(Instant.parse("2026-11-19T18:00:00.000001Z"));
  }

  @Test
  void businessZoneIsConfigurable() {
    SystemBusinessDateProvider provider =
        new SystemBusinessDateProvider(
            Clock.fixed(Instant.parse("2026-11-19T23:30:00Z"), ZoneOffset.UTC),
            ZoneId.of("Asia/Tokyo"));

    assertThat(provider.today()).isEqualTo(LocalDate.of(2026, 11, 20));
  }
}
