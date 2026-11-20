package com.banamex.transferlimits.infrastructure.observability;

import io.micrometer.common.KeyValue;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * HTTP server observation convention that masks the account identifier in the high-cardinality
 * {@code http.url} key value (exported as a span attribute), using {@link AccountIdMasker}.
 *
 * <p>The low-cardinality {@code uri} key already holds the URI template ({@code
 * /api/v1/accounts/{accountId}/limits}), so only {@code http.url} carries the raw path. When the
 * request is unknown the default {@code UNKNOWN} value passes through unchanged.
 */
public class MaskingServerRequestObservationConvention
    extends DefaultServerRequestObservationConvention {

  @Override
  protected KeyValue httpUrl(ServerRequestObservationContext context) {
    KeyValue original = super.httpUrl(context);
    return KeyValue.of(original.getKey(), AccountIdMasker.maskPath(original.getValue()));
  }
}
