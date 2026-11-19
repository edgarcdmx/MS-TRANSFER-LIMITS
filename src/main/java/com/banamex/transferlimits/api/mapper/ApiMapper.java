package com.banamex.transferlimits.api.mapper;

import com.banamex.transferlimits.api.dto.AccountLimitsResponse;
import com.banamex.transferlimits.api.dto.ChannelLimitResponse;
import com.banamex.transferlimits.api.dto.TransferValidationRequest;
import com.banamex.transferlimits.api.dto.TransferValidationResponse;
import com.banamex.transferlimits.domain.AccountLimits;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.ChannelUsage;
import com.banamex.transferlimits.domain.TransferRequest;
import com.banamex.transferlimits.domain.TransferValidation;

/** Manual mapping between API records and domain objects. */
public final class ApiMapper {

  private ApiMapper() {}

  /**
   * Maps the limits of an account.
   *
   * @param accountLimits domain limits with the usage of the day
   * @return the response body
   */
  public static AccountLimitsResponse toResponse(AccountLimits accountLimits) {
    return new AccountLimitsResponse(
        accountLimits.accountId(),
        accountLimits.businessDate(),
        accountLimits.channels().stream().map(ApiMapper::toResponse).toList());
  }

  /**
   * Maps the limit and usage of one channel.
   *
   * @param usage domain channel usage
   * @return the response body
   */
  public static ChannelLimitResponse toResponse(ChannelUsage usage) {
    ChannelLimit limit = usage.limit();
    return new ChannelLimitResponse(
        limit.channel(),
        limit.currency(),
        limit.dailyLimit(),
        limit.perOperationLimit(),
        usage.consumedToday(),
        usage.availableToday(),
        limit.updatedAt());
  }

  /**
   * Maps an approved transfer validation.
   *
   * @param validation domain validation
   * @return the response body
   */
  public static TransferValidationResponse toResponse(TransferValidation validation) {
    return new TransferValidationResponse(
        validation.transferId(),
        validation.accountId(),
        validation.status(),
        validation.channel(),
        validation.amount(),
        validation.currency(),
        validation.dailyLimit(),
        validation.perOperationLimit(),
        validation.consumedToday(),
        validation.availableToday(),
        validation.businessDate(),
        validation.validatedAt());
  }

  /**
   * Builds the domain request of a transfer validation.
   *
   * @param accountId account identifier from the path
   * @param request request body
   * @return the domain request
   */
  public static TransferRequest toDomain(String accountId, TransferValidationRequest request) {
    return new TransferRequest(
        request.transferId(), accountId, request.channel(), request.amount(), request.currency());
  }
}
