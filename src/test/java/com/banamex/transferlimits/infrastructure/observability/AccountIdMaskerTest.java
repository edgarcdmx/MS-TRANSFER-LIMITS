package com.banamex.transferlimits.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountIdMaskerTest {

  @ParameterizedTest
  @CsvSource({
    "ACC-0012345, ****2345",
    "12345, ****2345",
    "1234, ****",
    "A, ****",
  })
  void masksAllButTheLastFourCharacters(String accountId, String expected) {
    assertThat(AccountIdMasker.mask(accountId)).isEqualTo(expected);
  }

  @Test
  void maskOfNullIsNull() {
    assertThat(AccountIdMasker.mask(null)).isNull();
    assertThat(AccountIdMasker.maskPath(null)).isNull();
  }

  @ParameterizedTest
  @CsvSource({
    "/api/v1/accounts/ACC-0012345/limits, /api/v1/accounts/****2345/limits",
    "/api/v1/accounts/ACC-0012345/limits/WEB, /api/v1/accounts/****2345/limits/WEB",
    "/api/v1/accounts/ACC-0012345/transfer-validations,"
        + " /api/v1/accounts/****2345/transfer-validations",
    "/api/v1/accounts/ACC-0012345, /api/v1/accounts/****2345",
    "/api/v1/accounts/ACC-0012345?x=1, /api/v1/accounts/****2345?x=1",
    "/api/v1/accounts/ABC/limits, /api/v1/accounts/****/limits",
    "/a/accounts/ACC-1111/b/accounts/ACC-2222, /a/accounts/****1111/b/accounts/****2222",
    "/api/v1/accounts/, /api/v1/accounts/",
    "/actuator/health, /actuator/health",
    "UNKNOWN, UNKNOWN",
  })
  void masksTheAccountSegmentOfPaths(String path, String expected) {
    assertThat(AccountIdMasker.maskPath(path)).isEqualTo(expected);
  }

  @Test
  void treatsReplacementCharactersLiterally() {
    assertThat(AccountIdMasker.maskPath("/accounts/x$1\\y$2")).isEqualTo("/accounts/****\\y$2");
  }
}
