package com.banamex.transferlimits.api;

import com.banamex.transferlimits.api.dto.AccountLimitsResponse;
import com.banamex.transferlimits.api.dto.ApiConstraints;
import com.banamex.transferlimits.api.dto.ChannelLimitResponse;
import com.banamex.transferlimits.api.dto.UpdateChannelLimitRequest;
import com.banamex.transferlimits.api.mapper.ApiMapper;
import com.banamex.transferlimits.application.GetAccountLimitsUseCase;
import com.banamex.transferlimits.application.UpdateChannelLimitUseCase;
import com.banamex.transferlimits.domain.Channel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operations {@code getAccountLimits} and {@code updateChannelLimit} of {@code openapi.yaml}. */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/limits")
public class AccountLimitsController {

  private final GetAccountLimitsUseCase getAccountLimitsUseCase;
  private final UpdateChannelLimitUseCase updateChannelLimitUseCase;

  /**
   * Creates the controller.
   *
   * @param getAccountLimitsUseCase query use case
   * @param updateChannelLimitUseCase upsert use case
   */
  public AccountLimitsController(
      GetAccountLimitsUseCase getAccountLimitsUseCase,
      UpdateChannelLimitUseCase updateChannelLimitUseCase) {
    this.getAccountLimitsUseCase = getAccountLimitsUseCase;
    this.updateChannelLimitUseCase = updateChannelLimitUseCase;
  }

  /**
   * Returns the limits and the consumption of the day of every configured channel.
   *
   * @param accountId account identifier
   * @return 200 with the limits
   */
  @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
  public AccountLimitsResponse getAccountLimits(
      @PathVariable
          @Pattern(
              regexp = ApiConstraints.ACCOUNT_ID_PATTERN,
              message = ApiConstraints.ACCOUNT_ID_INVALID)
          String accountId) {
    return ApiMapper.toResponse(getAccountLimitsUseCase.execute(accountId));
  }

  /**
   * Creates or updates the limits of a channel.
   *
   * @param accountId account identifier
   * @param channelId channel to configure
   * @param request new limits
   * @return 200 with the stored limit and the consumption of the day
   */
  @PutMapping(
      path = "/{channelId}",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ChannelLimitResponse updateChannelLimit(
      @PathVariable
          @Pattern(
              regexp = ApiConstraints.ACCOUNT_ID_PATTERN,
              message = ApiConstraints.ACCOUNT_ID_INVALID)
          String accountId,
      @PathVariable Channel channelId,
      @Valid @RequestBody UpdateChannelLimitRequest request) {
    return ApiMapper.toResponse(
        updateChannelLimitUseCase.execute(
            accountId,
            channelId,
            request.dailyLimit(),
            request.perOperationLimit(),
            request.currency()));
  }
}
