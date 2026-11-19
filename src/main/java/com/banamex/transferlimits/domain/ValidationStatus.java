package com.banamex.transferlimits.domain;

/** Outcome of a transfer validation. */
public enum ValidationStatus {
  /** The transfer is within limits and its amount was consumed. */
  APPROVED,
  /** The transfer exceeds a limit; nothing was consumed. */
  REJECTED
}
