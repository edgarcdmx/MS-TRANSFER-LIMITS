package com.banamex.transferlimits.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TransferRequestTest {

  private static final UUID TRANSFER_ID = UUID.fromString("7d1b0f7e-3c2a-4a55-9a51-1c2b3d4e5f60");

  @ParameterizedTest
  @CsvSource({"0.01, 0.01", "10, 10.00", "10.5, 10.50", "99.990, 99.99"})
  void normalizesAmount(String amount, String expected) {
    TransferRequest request =
        new TransferRequest(TRANSFER_ID, "ACC-001", Channel.WEB, new BigDecimal(amount), "MXN");

    assertThat(request.amount()).hasToString(expected);
    assertThat(request.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(request.accountId()).isEqualTo("ACC-001");
    assertThat(request.channel()).isEqualTo(Channel.WEB);
    assertThat(request.currency()).isEqualTo("MXN");
  }

  @Test
  void nullCurrencyDefaultsToMxn() {
    TransferRequest request =
        new TransferRequest(TRANSFER_ID, "ACC-001", Channel.WEB, BigDecimal.ONE, null);

    assertThat(request.currency()).isEqualTo(Amounts.DEFAULT_CURRENCY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "-0.01", "-100"})
  void rejectsNonPositiveAmounts(String amount) {
    assertThatThrownBy(() ->
            new TransferRequest(TRANSFER_ID, "ACC-001", Channel.WEB, new BigDecimal(amount), "MXN"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount must be greater than 0");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.001", "10.555", "1.0001"})
  void rejectsMoreThanTwoDecimals(String amount) {
    assertThatThrownBy(() ->
            new TransferRequest(TRANSFER_ID, "ACC-001", Channel.WEB, new BigDecimal(amount), "MXN"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("amount must have at most 2 decimals");
  }

  @Test
  void rejectsNullFields() {
    assertThatThrownBy(
            () -> new TransferRequest(null, "ACC-001", Channel.WEB, BigDecimal.ONE, "MXN"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("transferId");
    assertThatThrownBy(
            () -> new TransferRequest(TRANSFER_ID, null, Channel.WEB, BigDecimal.ONE, "MXN"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("accountId");
    assertThatThrownBy(
            () -> new TransferRequest(TRANSFER_ID, "ACC-001", null, BigDecimal.ONE, "MXN"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("channel");
    assertThatThrownBy(() -> new TransferRequest(TRANSFER_ID, "ACC-001", Channel.WEB, null, "MXN"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("amount");
  }
}
