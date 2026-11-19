package com.banamex.transferlimits.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Request to validate a transfer against the limits of an account and channel.
 *
 * @param transferId client generated idempotency key
 * @param accountId account identifier
 * @param channel origination channel
 * @param amount amount to transfer, strictly positive with at most 2 decimals (normalized to scale
 *     2)
 * @param currency ISO 4217 currency code; null defaults to {@link Amounts#DEFAULT_CURRENCY}
 */
public record TransferRequest(
    UUID transferId, String accountId, Channel channel, BigDecimal amount, String currency) {

  /**
   * Validates and normalizes the request.
   *
   * @throws IllegalArgumentException if the amount is not positive or has more than 2 decimals
   */
  public TransferRequest {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(accountId, "accountId");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(amount, "amount");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be greater than 0");
    }
    if (!Amounts.hasValidScale(amount)) {
      throw new IllegalArgumentException("amount must have at most 2 decimals");
    }
    amount = Amounts.normalize(amount);
    currency = currency == null ? Amounts.DEFAULT_CURRENCY : currency;
  }
}
