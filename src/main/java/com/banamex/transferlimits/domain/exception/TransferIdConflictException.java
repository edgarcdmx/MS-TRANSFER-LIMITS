package com.banamex.transferlimits.domain.exception;

import java.util.UUID;

/** A {@code transferId} was reused with a different payload (HTTP 409). */
public class TransferIdConflictException extends DomainException {

  /** Error code of this exception. */
  public static final String CODE = "TRANSFER_ID_CONFLICT";

  private static final long serialVersionUID = 1L;

  private final UUID transferId;
  private final String accountId;

  /**
   * Creates the exception.
   *
   * @param transferId reused idempotency key
   * @param accountId account of the conflicting (new) request
   */
  public TransferIdConflictException(UUID transferId, String accountId) {
    super("transferId " + transferId + " was already used with a different payload");
    this.transferId = transferId;
    this.accountId = accountId;
  }

  @Override
  public String code() {
    return CODE;
  }

  /**
   * Reused idempotency key.
   *
   * @return the transfer id
   */
  public UUID transferId() {
    return transferId;
  }

  /**
   * Account of the conflicting request.
   *
   * @return the account identifier
   */
  public String accountId() {
    return accountId;
  }
}
