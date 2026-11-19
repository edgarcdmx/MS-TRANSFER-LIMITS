package com.banamex.transferlimits.config.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** Observation tuning shared by tracing and metrics. */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

  private static final String ACTUATOR_PATH_PREFIX = "/actuator";

  /**
   * Skips observations (and therefore spans) for actuator requests, so container health checks do
   * not flood the tracing backend.
   *
   * @return predicate that rejects server observations of actuator endpoints
   */
  @Bean
  ObservationPredicate ignoreActuatorRequests() {
    return (name, context) -> {
      if (context instanceof ServerRequestObservationContext serverContext) {
        return !serverContext.getCarrier().getRequestURI().startsWith(ACTUATOR_PATH_PREFIX);
      }
      return true;
    };
  }
}
