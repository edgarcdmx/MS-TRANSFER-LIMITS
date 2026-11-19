package com.banamex.transferlimits.domain;

/** Channel through which a transfer is originated. */
public enum Channel {
  /** Mobile banking application. */
  MOBILE_APP,
  /** Web banking portal. */
  WEB,
  /** Bank branch (teller). */
  BRANCH
}
