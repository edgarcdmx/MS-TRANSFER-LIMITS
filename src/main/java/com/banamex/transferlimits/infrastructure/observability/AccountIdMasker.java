package com.banamex.transferlimits.infrastructure.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks account identifiers in request paths before they are exported as span attributes.
 *
 * <p>The path segment that follows {@code /accounts/} is replaced by {@code ****} plus its last
 * four characters ({@code /api/v1/accounts/ACC-0012345/limits ->
 * /api/v1/accounts/****2345/limits}). Identifiers of four characters or fewer are fully masked, so
 * no short id is ever disclosed.
 */
public final class AccountIdMasker {

  /** Prefix that replaces the hidden part of an account identifier. */
  public static final String MASK = "****";

  private static final int VISIBLE_CHARS = 4;
  private static final Pattern ACCOUNT_SEGMENT = Pattern.compile("(/accounts/)([^/?#;]+)");

  private AccountIdMasker() {}

  /**
   * Masks one account identifier.
   *
   * @param accountId raw identifier (may be null)
   * @return {@code ****} plus the last four characters, {@code ****} alone when the identifier has
   *     four characters or fewer, or {@code null} for a null input
   */
  public static String mask(String accountId) {
    if (accountId == null) {
      return null;
    }
    if (accountId.length() <= VISIBLE_CHARS) {
      return MASK;
    }
    return MASK + accountId.substring(accountId.length() - VISIBLE_CHARS);
  }

  /**
   * Masks every account identifier found after {@code /accounts/} in a path or URL.
   *
   * @param path request path or URL (may be null)
   * @return the path with masked account identifiers, or {@code null} for a null input
   */
  public static String maskPath(String path) {
    if (path == null) {
      return null;
    }
    Matcher matcher = ACCOUNT_SEGMENT.matcher(path);
    return matcher.replaceAll(
        match -> Matcher.quoteReplacement(match.group(1) + mask(match.group(2))));
  }
}
