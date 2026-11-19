package com.banamex.transferlimits.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Result of validating a transfer, as persisted for idempotency.
 *
 * <p>The limit and consumption values are a snapshot taken at validation time: for an approved
 * transfer {@code consumedToday} includes its own amount; for a rejected one it is the consumption
 * observed when the rejection was decided.
 *
 * @param transferId idempotency key
 * @param accountId account identifier
 * @param channel origination channel
 * @param amount transfer amount (scale 2)
 * @param currency ISO 4217 currency code
 * @param status approval outcome
 * @param reason rejection reason, {@code null} if and only if approved
 * @param dailyLimit daily limit at validation time
 * @param perOperationLimit per-operation limit at validation time
 * @param consumedToday consumption of the business date after this validation
 * @param availableToday {@code max(0, dailyLimit - consumedToday)}
 * @param businessDate business date the consumption belongs to
 * @param validatedAt instant of the original validation
 * @param replayed {@code true} when returned for a repeated {@code transferId}
 */
public record TransferValidation(
    UUID transferId,
    String accountId,
    Channel channel,
    BigDecimal amount,
    String currency,
    ValidationStatus status,
    RejectionReason reason,
    BigDecimal dailyLimit,
    BigDecimal perOperationLimit,
    BigDecimal consumedToday,
    BigDecimal availableToday,
    LocalDate businessDate,
    Instant validatedAt,
    boolean replayed) {

  /**
   * Validates the status/reason consistency.
   *
   * @throws IllegalArgumentException if a reason is given for an approval or missing for a
   *     rejection
   */
  public TransferValidation {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(status, "status");
    if ((status == ValidationStatus.APPROVED) != (reason == null)) {
      throw new IllegalArgumentException("reason must be present only for rejected validations");
    }
  }

  /**
   * Tells whether the transfer was approved.
   *
   * @return {@code true} if the status is {@link ValidationStatus#APPROVED}
   */
  public boolean approved() {
    return status == ValidationStatus.APPROVED;
  }

  /**
   * Tells whether a new request carries the same payload as this validation.
   *
   * @param request request with the same transfer id
   * @return {@code true} if account, channel, amount and currency are equal
   */
  public boolean samePayloadAs(TransferRequest request) {
    return accountId.equals(request.accountId())
        && channel == request.channel()
        && amount.compareTo(request.amount()) == 0
        && currency.equals(request.currency());
  }

  /**
   * Returns a copy marked as an idempotent replay.
   *
   * @return this validation with {@code replayed = true}
   */
  public TransferValidation asReplay() {
    return new TransferValidation(
        transferId,
        accountId,
        channel,
        amount,
        currency,
        status,
        reason,
        dailyLimit,
        perOperationLimit,
        consumedToday,
        availableToday,
        businessDate,
        validatedAt,
        true);
  }
}
