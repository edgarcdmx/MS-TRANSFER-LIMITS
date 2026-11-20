package com.banamex.transferlimits.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MaskingServerRequestObservationConventionTest {

  private static final String HTTP_URL = "http.url";

  private final MaskingServerRequestObservationConvention convention =
      new MaskingServerRequestObservationConvention();

  private static String value(KeyValues keyValues, String key) {
    return keyValues.stream()
        .filter(keyValue -> keyValue.getKey().equals(key))
        .map(KeyValue::getValue)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void masksTheAccountIdInTheHttpUrl() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/accounts/ACC-0012345/limits");
    ServerRequestObservationContext context =
        new ServerRequestObservationContext(request, new MockHttpServletResponse());

    KeyValues high = convention.getHighCardinalityKeyValues(context);

    assertThat(value(high, HTTP_URL)).isEqualTo("/api/v1/accounts/****2345/limits");
    assertThat(high.stream()).noneMatch(keyValue -> keyValue.getValue().contains("0012345"));
    assertThat(convention.getName()).isEqualTo("http.server.requests");
  }

  @Test
  void keepsPathsWithoutAccounts() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
    ServerRequestObservationContext context =
        new ServerRequestObservationContext(request, new MockHttpServletResponse());

    assertThat(value(convention.getHighCardinalityKeyValues(context), HTTP_URL))
        .isEqualTo("/v3/api-docs");
  }

  @Test
  void lowCardinalityKeysUseTheUriTemplate() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/accounts/ACC-0012345/limits");
    ServerRequestObservationContext context =
        new ServerRequestObservationContext(request, new MockHttpServletResponse());
    context.setPathPattern("/api/v1/accounts/{accountId}/limits");

    KeyValues low = convention.getLowCardinalityKeyValues(context);

    assertThat(value(low, "uri")).isEqualTo("/api/v1/accounts/{accountId}/limits");
    assertThat(low.stream()).noneMatch(keyValue -> keyValue.getValue().contains("0012345"));
  }
}
