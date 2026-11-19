package com.banamex.transferlimits.domain;

/** Reason why a transfer validation was rejected. */
public enum RejectionReason {
  /** The amount is greater than the per-operation limit of the channel. */
  PER_OPERATION_LIMIT_EXCEEDED,
  /** The amount plus what was already consumed today exceeds the daily limit. */
  DAILY_LIMIT_EXCEEDED
}
