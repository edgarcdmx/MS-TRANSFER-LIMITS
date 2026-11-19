package com.banamex.transferlimits.domain.exception;

/**
 * Base class of business exceptions. Each subclass exposes a stable machine readable {@link
 * #code()} that the web layer maps to an RFC 9457 problem type.
 */
public abstract class DomainException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message technical message (not shown to clients as-is)
   */
  protected DomainException(String message) {
    super(message);
  }

  /**
   * Stable error code, e.g. {@code LIMIT_NOT_FOUND}.
   *
   * @return the error code
   */
  public abstract String code();
}
