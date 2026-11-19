package com.banamex.transferlimits.api.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code AccountLimitsResponse} schema: limits and consumption of every configured channel.
 *
 * @param accountId account identifier
 * @param businessDate business date used to compute the consumption
 * @param channels limits and consumption per channel
 */
public record AccountLimitsResponse(
    String accountId, LocalDate businessDate, List<ChannelLimitResponse> channels) {}
