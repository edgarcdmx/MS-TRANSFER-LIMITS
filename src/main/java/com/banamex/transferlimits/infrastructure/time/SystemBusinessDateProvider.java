package com.banamex.transferlimits.infrastructure.time;

import com.banamex.transferlimits.domain.port.BusinessDateProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link BusinessDateProvider} backed by an injected {@link Clock} and the business time zone
 * configured in {@code transfer-limits.business-zone} (default {@code America/Mexico_City}).
 */
@Component
public class SystemBusinessDateProvider implements BusinessDateProvider {

  private final Clock clock;
  private final ZoneId businessZone;

  /**
   * Creates the provider.
   *
   * @param clock source of the current instant
   * @param businessZone time zone that defines the business date
   */
  public SystemBusinessDateProvider(
      Clock clock,
      @Value("${transfer-limits.business-zone:America/Mexico_City}") ZoneId businessZone) {
    this.clock = clock;
    this.businessZone = businessZone;
  }

  /**
   * Current instant truncated to microseconds, the precision of PostgreSQL {@code TIMESTAMPTZ}, so
   * a stored and later replayed value is identical to the one first returned.
   */
  @Override
  public Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  @Override
  public LocalDate businessDateOf(Instant instant) {
    return LocalDate.ofInstant(instant, businessZone);
  }
}
