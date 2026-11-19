package com.banamex.transferlimits.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferValidationTest {

  private static final UUID TRANSFER_ID = UUID.fromString("7d1b0f7e-3c2a-4a55-9a51-1c2b3d4e5f60");
  private static final LocalDate DATE = LocalDate.of(2026, 11, 19);
  private static final Instant VALIDATED_AT = Instant.parse("2026-11-19T18:00:00.123456Z");

  private static TransferValidation validation(
      BigDecimal amount, ValidationStatus status, RejectionReason reason) {
    return new TransferValidation(
        TRANSFER_ID,
        "ACC-001",
        Channel.WEB,
        amount,
        "MXN",
        status,
        reason,
        new BigDecimal("5000.00"),
        new BigDecimal("1000.00"),
        new BigDecimal("1500.00"),
        new BigDecimal("3500.00"),
        DATE,
        VALIDATED_AT,
        false);
  }

  private static TransferValidation approved() {
    return validation(new BigDecimal("500.00"), ValidationStatus.APPROVED, null);
  }

  private static TransferRequest request(
      String accountId, Channel channel, String amount, String currency) {
    return new TransferRequest(TRANSFER_ID, accountId, channel, new BigDecimal(amount), currency);
  }

  @Test
  void approvedReflectsStatus() {
    assertThat(approved().approved()).isTrue();
    assertThat(
            validation(
                    new BigDecimal("500.00"),
                    ValidationStatus.REJECTED,
                    RejectionReason.DAILY_LIMIT_EXCEEDED)
                .approved())
        .isFalse();
  }

  @Test
  void reasonMustBePresentOnlyForRejections() {
    assertThatThrownBy(() ->
            validation(
                BigDecimal.TEN,
                ValidationStatus.APPROVED,
                RejectionReason.PER_OPERATION_LIMIT_EXCEEDED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> validation(BigDecimal.TEN, ValidationStatus.REJECTED, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresTransferIdAndStatus() {
    assertThatThrownBy(() ->
            new TransferValidation(
                null,
                "ACC-001",
                Channel.WEB,
                BigDecimal.TEN,
                "MXN",
                ValidationStatus.APPROVED,
                null,
                null,
                null,
                null,
                null,
                DATE,
                VALIDATED_AT,
                false))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("transferId");
    assertThatThrownBy(() ->
            new TransferValidation(
                TRANSFER_ID,
                "ACC-001",
                Channel.WEB,
                BigDecimal.TEN,
                "MXN",
                null,
                null,
                null,
                null,
                null,
                null,
                DATE,
                VALIDATED_AT,
                false))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("status");
  }

  @Test
  void samePayloadWhenAccountChannelAmountAndCurrencyMatch() {
    assertThat(approved().samePayloadAs(request("ACC-001", Channel.WEB, "500", "MXN"))).isTrue();
    assertThat(approved().samePayloadAs(request("ACC-001", Channel.WEB, "500.0", null))).isTrue();
  }

  @Test
  void samePayloadComparesAmountIgnoringScale() {
    TransferValidation stored =
        validation(new BigDecimal("500.0"), ValidationStatus.APPROVED, null);

    assertThat(stored.samePayloadAs(request("ACC-001", Channel.WEB, "500.00", "MXN"))).isTrue();
  }

  @Test
  void differentAccountIsNotSamePayload() {
    assertThat(approved().samePayloadAs(request("ACC-002", Channel.WEB, "500", "MXN"))).isFalse();
  }

  @Test
  void differentChannelIsNotSamePayload() {
    assertThat(approved().samePayloadAs(request("ACC-001", Channel.BRANCH, "500", "MXN")))
        .isFalse();
  }

  @Test
  void differentAmountIsNotSamePayload() {
    assertThat(approved().samePayloadAs(request("ACC-001", Channel.WEB, "500.01", "MXN")))
        .isFalse();
  }

  @Test
  void differentCurrencyIsNotSamePayload() {
    assertThat(approved().samePayloadAs(request("ACC-001", Channel.WEB, "500", "USD"))).isFalse();
  }

  @Test
  void asReplayCopiesEveryFieldAndMarksReplayed() {
    TransferValidation original =
        validation(
            new BigDecimal("1200.00"),
            ValidationStatus.REJECTED,
            RejectionReason.PER_OPERATION_LIMIT_EXCEEDED);

    TransferValidation replay = original.asReplay();

    assertThat(replay.replayed()).isTrue();
    assertThat(original.replayed()).isFalse();
    assertThat(replay).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(original);
    assertThat(replay.reason()).isEqualTo(RejectionReason.PER_OPERATION_LIMIT_EXCEEDED);
    assertThat(replay.validatedAt()).isEqualTo(VALIDATED_AT);
    assertThat(replay.businessDate()).isEqualTo(DATE);
  }
}
