package com.banamex.transferlimits.api.dto;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ValidationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code TransferValidationResponse} schema: an approved transfer validation.
 *
 * @param transferId idempotency key
 * @param accountId account identifier
 * @param status always {@code APPROVED} (rejections are 422 problems)
 * @param channel origination channel
 * @param amount transferred amount
 * @param currency ISO 4217 currency
 * @param dailyLimit daily limit at validation time
 * @param perOperationLimit per-operation limit at validation time
 * @param consumedToday consumption of the day including this transfer
 * @param availableToday available amount of the day after this transfer
 * @param businessDate business date of the consumption
 * @param validatedAt instant of the original validation
 */
public record TransferValidationResponse(
    UUID transferId,
    String accountId,
    ValidationStatus status,
    Channel channel,
    BigDecimal amount,
    String currency,
    BigDecimal dailyLimit,
    BigDecimal perOperationLimit,
    BigDecimal consumedToday,
    BigDecimal availableToday,
    LocalDate businessDate,
    Instant validatedAt) {}
