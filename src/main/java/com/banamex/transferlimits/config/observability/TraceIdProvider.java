package com.banamex.transferlimits.config.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Exposes the trace id of the current span so it can be attached to responses and errors. */
@Component
public class TraceIdProvider {

  private final ObjectProvider<Tracer> tracerProvider;

  /**
   * Creates the provider.
   *
   * @param tracerProvider lazy access to the tracer; it may be absent (e.g. in test slices)
   */
  public TraceIdProvider(ObjectProvider<Tracer> tracerProvider) {
    this.tracerProvider = tracerProvider;
  }

  /**
   * Returns the trace id of the span active on the current thread.
   *
   * @return the trace id, or empty when there is no tracer, no current span or a no-op span
   */
  public Optional<String> currentTraceId() {
    Tracer tracer = tracerProvider.getIfAvailable();
    if (tracer == null) {
      return Optional.empty();
    }
    Span span = tracer.currentSpan();
    if (span == null) {
      return Optional.empty();
    }
    String traceId = span.context().traceId();
    return traceId == null || traceId.isBlank() ? Optional.empty() : Optional.of(traceId);
  }
}
