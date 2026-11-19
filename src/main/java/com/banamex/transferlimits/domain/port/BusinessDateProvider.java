package com.banamex.transferlimits.domain.port;

import java.time.Instant;
import java.time.LocalDate;

/** Source of the current instant and of the business date (bank time zone). */
public interface BusinessDateProvider {

  /**
   * Current instant.
   *
   * @return now
   */
  Instant now();

  /**
   * Business date that contains an instant.
   *
   * @param instant instant to convert
   * @return the date of the instant in the business time zone
   */
  LocalDate businessDateOf(Instant instant);

  /**
   * Current business date.
   *
   * @return the business date of {@link #now()}
   */
  default LocalDate today() {
    return businessDateOf(now());
  }
}
