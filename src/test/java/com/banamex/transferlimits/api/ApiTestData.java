package com.banamex.transferlimits.api;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.ChannelUsage;
import com.banamex.transferlimits.domain.RejectionReason;
import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.ValidationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Fixtures shared by the web layer tests. */
public final class ApiTestData {

  /** Account used by the examples of the contract. */
  public static final String ACCOUNT_ID = "0012345678";

  /** Transfer id used by the examples of the contract. */
  public static final UUID TRANSFER_ID = UUID.fromString("3f1c2b9e-7a4d-4c1e-9b8a-2d6f5e4c3b2a");

  /** Business date of the examples. */
  public static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-11-19");

  /** Validation instant of the examples. */
  public static final Instant VALIDATED_AT = Instant.parse("2026-11-19T17:21:30.123Z");

  /** Last update of the limits of the examples. */
  public static final Instant UPDATED_AT = Instant.parse("2026-11-18T15:04:05Z");

  /** Trace id returned by the mocked provider. */
  public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private ApiTestData() {}

  /**
   * Builds an amount with scale 2.
   *
   * @param value textual amount
   * @return the amount
   */
  public static BigDecimal amount(String value) {
    return new BigDecimal(value);
  }

  /**
   * Builds the usage of a channel.
   *
   * @param channel channel
   * @param daily daily limit
   * @param perOperation per-operation limit
   * @param consumed consumed today
   * @return the usage
   */
  public static ChannelUsage usage(
      Channel channel, String daily, String perOperation, String consumed) {
    return new ChannelUsage(
        new ChannelLimit(
            ACCOUNT_ID, channel, amount(daily), amount(perOperation), "MXN", UPDATED_AT),
        amount(consumed));
  }

  /**
   * Approved validation of 1,500.00 MXN by MOBILE_APP.
   *
   * @param replayed whether it is an idempotent replay
   * @return the validation
   */
  public static TransferValidation approved(boolean replayed) {
    return new TransferValidation(
        TRANSFER_ID,
        ACCOUNT_ID,
        Channel.MOBILE_APP,
        amount("1500.00"),
        "MXN",
        ValidationStatus.APPROVED,
        null,
        amount("50000.00"),
        amount("20000.00"),
        amount("1500.00"),
        amount("48500.00"),
        BUSINESS_DATE,
        VALIDATED_AT,
        replayed);
  }

  /**
   * Rejected validation.
   *
   * @param reason rejection reason
   * @param amount transfer amount
   * @param consumed consumption observed when rejecting
   * @param replayed whether it is an idempotent replay
   * @return the validation
   */
  public static TransferValidation rejected(
      RejectionReason reason, String amount, String consumed, boolean replayed) {
    BigDecimal daily = amount("50000.00");
    BigDecimal consumedToday = amount(consumed);
    return new TransferValidation(
        TRANSFER_ID,
        ACCOUNT_ID,
        Channel.MOBILE_APP,
        amount(amount),
        "MXN",
        ValidationStatus.REJECTED,
        reason,
        daily,
        amount("20000.00"),
        consumedToday,
        daily.subtract(consumedToday),
        BUSINESS_DATE,
        VALIDATED_AT,
        replayed);
  }
}
