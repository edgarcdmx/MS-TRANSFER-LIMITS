package com.banamex.transferlimits.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Time related beans; tests replace the {@link Clock} with a fixed one. */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

  /**
   * System clock in UTC; the business zone is applied by the business date provider.
   *
   * @return the UTC system clock
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
