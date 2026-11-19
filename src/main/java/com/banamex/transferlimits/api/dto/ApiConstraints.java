package com.banamex.transferlimits.api.dto;

/**
 * Bean Validation patterns and (Spanish) messages shared by the API, aligned with the constraints
 * of {@code openapi.yaml}. Messages are explicit so they do not depend on the request locale.
 */
public final class ApiConstraints {

  /** {@code AccountId} schema pattern. */
  public static final String ACCOUNT_ID_PATTERN = "^[A-Za-z0-9-]{1,34}$";

  /** {@code Currency} schema pattern (only MXN is supported). */
  public static final String CURRENCY_PATTERN = "^MXN$";

  /** Integer digits allowed in an amount ({@code NUMERIC(19,2)}). */
  public static final int AMOUNT_INTEGER_DIGITS = 17;

  /** Fraction digits allowed in an amount. */
  public static final int AMOUNT_FRACTION_DIGITS = 2;

  /** Message for a missing value. */
  public static final String NOT_NULL = "no debe ser nulo";

  /** Message for an invalid account id. */
  public static final String ACCOUNT_ID_INVALID =
      "debe contener sólo letras, dígitos o guiones (1 a 34 caracteres)";

  /** Message for an unsupported currency. */
  public static final String CURRENCY_INVALID = "sólo se admite MXN";

  /** Message for a non positive transfer amount. */
  public static final String AMOUNT_POSITIVE = "debe ser mayor que 0";

  /** Message for a negative limit. */
  public static final String AMOUNT_NOT_NEGATIVE = "debe ser mayor o igual a 0";

  /** Message for an amount with too many digits. */
  public static final String AMOUNT_DIGITS = "debe tener máximo 17 enteros y 2 decimales";

  private ApiConstraints() {}
}
