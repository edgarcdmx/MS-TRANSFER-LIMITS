package com.banamex.transferlimits.domain.exception;

/** The account has no limit configured for any channel (HTTP 404). */
public class AccountLimitsNotFoundException extends DomainException {

  /** Error code of this exception. */
  public static final String CODE = "ACCOUNT_LIMITS_NOT_FOUND";

  private static final long serialVersionUID = 1L;

  private final String accountId;

  /**
   * Creates the exception.
   *
   * @param accountId account without limits
   */
  public AccountLimitsNotFoundException(String accountId) {
    super("Account has no limits configured");
    this.accountId = accountId;
  }

  @Override
  public String code() {
    return CODE;
  }

  /**
   * Account without limits.
   *
   * @return the account identifier
   */
  public String accountId() {
    return accountId;
  }
}
