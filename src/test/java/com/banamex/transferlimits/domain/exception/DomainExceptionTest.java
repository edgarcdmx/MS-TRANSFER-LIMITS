package com.banamex.transferlimits.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.banamex.transferlimits.domain.Channel;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DomainExceptionTest {

  @Test
  void accountLimitsNotFound() {
    AccountLimitsNotFoundException exception = new AccountLimitsNotFoundException("ACC-404");

    assertThat(exception).isInstanceOf(DomainException.class);
    assertThat(exception.code()).isEqualTo("ACCOUNT_LIMITS_NOT_FOUND");
    assertThat(exception.code()).isEqualTo(AccountLimitsNotFoundException.CODE);
    assertThat(exception.accountId()).isEqualTo("ACC-404");
    assertThat(exception).hasMessage("Account ACC-404 has no limits configured");
  }

  @Test
  void limitNotFound() {
    LimitNotFoundException exception = new LimitNotFoundException("ACC-1", Channel.BRANCH);

    assertThat(exception).isInstanceOf(DomainException.class);
    assertThat(exception.code()).isEqualTo("LIMIT_NOT_FOUND");
    assertThat(exception.code()).isEqualTo(LimitNotFoundException.CODE);
    assertThat(exception.accountId()).isEqualTo("ACC-1");
    assertThat(exception.channel()).isEqualTo(Channel.BRANCH);
    assertThat(exception).hasMessage("Account ACC-1 has no limit configured for channel BRANCH");
  }

  @Test
  void invalidLimitConfiguration() {
    InvalidLimitConfigurationException exception =
        new InvalidLimitConfigurationException(
            "bad", "ACC-1", Channel.WEB, BigDecimal.ONE, BigDecimal.TEN);

    assertThat(exception).isInstanceOf(DomainException.class);
    assertThat(exception.code()).isEqualTo("INVALID_LIMIT_CONFIGURATION");
    assertThat(exception.code()).isEqualTo(InvalidLimitConfigurationException.CODE);
    assertThat(exception.accountId()).isEqualTo("ACC-1");
    assertThat(exception.channel()).isEqualTo(Channel.WEB);
    assertThat(exception.dailyLimit()).isEqualTo(BigDecimal.ONE);
    assertThat(exception.perOperationLimit()).isEqualTo(BigDecimal.TEN);
    assertThat(exception).hasMessage("bad");
  }

  @Test
  void transferIdConflict() {
    UUID transferId = UUID.fromString("7d1b0f7e-3c2a-4a55-9a51-1c2b3d4e5f60");

    TransferIdConflictException exception = new TransferIdConflictException(transferId, "ACC-1");

    assertThat(exception).isInstanceOf(DomainException.class);
    assertThat(exception.code()).isEqualTo("TRANSFER_ID_CONFLICT");
    assertThat(exception.code()).isEqualTo(TransferIdConflictException.CODE);
    assertThat(exception.transferId()).isEqualTo(transferId);
    assertThat(exception.accountId()).isEqualTo("ACC-1");
    assertThat(exception).hasMessageContaining(transferId.toString());
  }
}
