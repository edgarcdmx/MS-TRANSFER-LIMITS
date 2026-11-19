package com.banamex.transferlimits.api.dto;

import com.banamex.transferlimits.domain.Channel;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * {@code ChannelLimitResponse} schema: limits and consumption of the day of a channel.
 *
 * @param channel channel
 * @param currency ISO 4217 currency
 * @param dailyLimit daily limit of the channel
 * @param perOperationLimit per-operation limit of the channel
 * @param consumedToday amount approved on the current business date
 * @param availableToday {@code dailyLimit - consumedToday}, never negative
 * @param updatedAt last modification of the limit configuration
 */
public record ChannelLimitResponse(
    Channel channel,
    String currency,
    BigDecimal dailyLimit,
    BigDecimal perOperationLimit,
    BigDecimal consumedToday,
    BigDecimal availableToday,
    Instant updatedAt) {}
