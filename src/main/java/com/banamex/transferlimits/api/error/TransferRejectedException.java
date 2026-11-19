package com.banamex.transferlimits.api.error;

import com.banamex.transferlimits.domain.TransferValidation;

/**
 * Signals, inside the web layer, that a transfer validation was rejected so that {@link
 * GlobalExceptionHandler} renders it as a 422 problem with {@code reasonCode} and {@code
 * transferId}. It is a control-flow exception: no stack trace is captured.
 */
public class TransferRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient TransferValidation validation;

  /**
   * Creates the exception.
   *
   * @param validation rejected validation (fresh or replayed)
   */
  public TransferRejectedException(TransferValidation validation) {
    super(
        "Transfer " + validation.transferId() + " rejected: " + validation.reason(),
        null,
        false,
        false);
    this.validation = validation;
  }

  /**
   * Rejected validation.
   *
   * @return the validation
   */
  public TransferValidation validation() {
    return validation;
  }
}
