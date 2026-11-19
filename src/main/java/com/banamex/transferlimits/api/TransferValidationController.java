package com.banamex.transferlimits.api;

import com.banamex.transferlimits.api.dto.ApiConstraints;
import com.banamex.transferlimits.api.dto.TransferValidationRequest;
import com.banamex.transferlimits.api.dto.TransferValidationResponse;
import com.banamex.transferlimits.api.error.TransferRejectedException;
import com.banamex.transferlimits.api.mapper.ApiMapper;
import com.banamex.transferlimits.application.ValidateTransferUseCase;
import com.banamex.transferlimits.domain.TransferValidation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Operation {@code validateTransfer} of {@code openapi.yaml}. */
@RestController
@RequestMapping(TransferValidationController.BASE_PATH)
public class TransferValidationController {

  /** Collection path of the transfer validations of an account. */
  public static final String BASE_PATH = "/api/v1/accounts/{accountId}/transfer-validations";

  private final ValidateTransferUseCase validateTransferUseCase;

  /**
   * Creates the controller.
   *
   * @param validateTransferUseCase validation use case
   */
  public TransferValidationController(ValidateTransferUseCase validateTransferUseCase) {
    this.validateTransferUseCase = validateTransferUseCase;
  }

  /**
   * Validates a transfer and consumes the daily limit.
   *
   * <ul>
   *   <li>approved: 201 with {@code Location};
   *   <li>idempotent replay of an approved transfer: 200;
   *   <li>rejected (fresh or replayed): 422 problem, see {@link TransferRejectedException}.
   * </ul>
   *
   * @param accountId account identifier
   * @param request transfer to validate
   * @return the approved validation
   */
  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<TransferValidationResponse> validateTransfer(
      @PathVariable
          @Pattern(
              regexp = ApiConstraints.ACCOUNT_ID_PATTERN,
              message = ApiConstraints.ACCOUNT_ID_INVALID)
          String accountId,
      @Valid @RequestBody TransferValidationRequest request) {
    TransferValidation validation =
        validateTransferUseCase.execute(ApiMapper.toDomain(accountId, request));
    if (!validation.approved()) {
      throw new TransferRejectedException(validation);
    }
    TransferValidationResponse body = ApiMapper.toResponse(validation);
    if (validation.replayed()) {
      return ResponseEntity.ok(body);
    }
    URI location =
        UriComponentsBuilder.fromPath(BASE_PATH + "/{transferId}")
            .buildAndExpand(accountId, validation.transferId())
            .encode()
            .toUri();
    return ResponseEntity.created(location).body(body);
  }
}
