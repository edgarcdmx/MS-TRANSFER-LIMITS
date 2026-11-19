package com.banamex.transferlimits.api.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * {@code UpdateChannelLimitRequest} schema. The rule {@code perOperationLimit <= dailyLimit} is a
 * business rule checked by the domain (422 {@code INVALID_LIMIT_CONFIGURATION}).
 *
 * @param dailyLimit daily limit (non-negative, at most 2 decimals)
 * @param perOperationLimit per-operation limit (non-negative, at most 2 decimals)
 * @param currency ISO 4217 currency; optional, only MXN
 */
public record UpdateChannelLimitRequest(
    @NotNull(message = ApiConstraints.NOT_NULL)
        @PositiveOrZero(message = ApiConstraints.AMOUNT_NOT_NEGATIVE)
        @Digits(
            integer = ApiConstraints.AMOUNT_INTEGER_DIGITS,
            fraction = ApiConstraints.AMOUNT_FRACTION_DIGITS,
            message = ApiConstraints.AMOUNT_DIGITS)
        BigDecimal dailyLimit,
    @NotNull(message = ApiConstraints.NOT_NULL)
        @PositiveOrZero(message = ApiConstraints.AMOUNT_NOT_NEGATIVE)
        @Digits(
            integer = ApiConstraints.AMOUNT_INTEGER_DIGITS,
            fraction = ApiConstraints.AMOUNT_FRACTION_DIGITS,
            message = ApiConstraints.AMOUNT_DIGITS)
        BigDecimal perOperationLimit,
    @Pattern(regexp = ApiConstraints.CURRENCY_PATTERN, message = ApiConstraints.CURRENCY_INVALID)
        String currency) {}
