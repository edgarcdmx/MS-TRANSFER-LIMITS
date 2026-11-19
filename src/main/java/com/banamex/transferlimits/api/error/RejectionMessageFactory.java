package com.banamex.transferlimits.api.error;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.TransferValidation;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.commons.text.StringSubstitutor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Component;

/**
 * Builds the human readable {@code detail} of problem responses from the templates of {@code
 * messages/rejections.properties} with Apache Commons Text {@link StringSubstitutor}.
 *
 * <p>Templates are loaded (UTF-8) and checked once at construction, so a missing template stops the
 * application at startup. Amounts are formatted with the {@code es-MX} locale and 2 decimals (e.g.
 * {@code 25,000.00}). Substitution inside variable values is disabled, so client supplied values
 * can never be expanded as templates.
 */
@Component
public class RejectionMessageFactory {

  /** Default classpath location of the templates. */
  public static final String DEFAULT_LOCATION = "messages/rejections.properties";

  /** Locale used to format amounts. */
  public static final Locale AMOUNT_LOCALE = Locale.forLanguageTag("es-MX");

  static final String INVALID_LIMIT_CONFIGURATION_GENERIC = "INVALID_LIMIT_CONFIGURATION_GENERIC";

  /** Keys that must be present (and not blank) in the templates file. */
  static final List<String> REQUIRED_KEYS =
      List.of(
          ProblemType.PER_OPERATION_LIMIT_EXCEEDED.code(),
          ProblemType.DAILY_LIMIT_EXCEEDED.code(),
          ProblemType.VALIDATION_ERROR.code(),
          ProblemType.ACCOUNT_LIMITS_NOT_FOUND.code(),
          ProblemType.LIMIT_NOT_FOUND.code(),
          ProblemType.TRANSFER_ID_CONFLICT.code(),
          ProblemType.INVALID_LIMIT_CONFIGURATION.code(),
          INVALID_LIMIT_CONFIGURATION_GENERIC,
          ProblemType.CONCURRENT_UPDATE.code(),
          ProblemType.INTERNAL_ERROR.code());

  private final Map<String, String> templates;

  /** Loads the templates from {@value #DEFAULT_LOCATION}. */
  public RejectionMessageFactory() {
    this(DEFAULT_LOCATION);
  }

  /**
   * Loads the templates from a classpath location.
   *
   * @param location classpath location of a UTF-8 properties file
   * @throws IllegalStateException if the file cannot be read or a required template is missing
   */
  RejectionMessageFactory(String location) {
    Properties properties = new Properties();
    try {
      PropertiesLoaderUtils.fillProperties(
          properties, new EncodedResource(new ClassPathResource(location), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load message templates from " + location, e);
    }
    List<String> missing =
        REQUIRED_KEYS.stream().filter(key -> properties.getProperty(key, "").isBlank()).toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException("Missing message templates " + missing + " in " + location);
    }
    Map<String, String> loaded = new HashMap<>();
    REQUIRED_KEYS.forEach(key -> loaded.put(key, properties.getProperty(key).strip()));
    this.templates = Map.copyOf(loaded);
  }

  /**
   * Detail of a rejected transfer ({@code PER_OPERATION_LIMIT_EXCEEDED} or {@code
   * DAILY_LIMIT_EXCEEDED}).
   *
   * @param validation rejected validation
   * @return the message for the rejection reason
   */
  public String rejection(TransferValidation validation) {
    Map<String, String> values = new HashMap<>();
    values.put("amount", format(validation.amount()));
    values.put("currency", validation.currency());
    values.put("channel", validation.channel().name());
    values.put("perOperationLimit", format(validation.perOperationLimit()));
    values.put("dailyLimit", format(validation.dailyLimit()));
    values.put("consumed", format(validation.consumedToday()));
    values.put("available", format(validation.availableToday()));
    return render(validation.reason().name(), values);
  }

  /**
   * Detail of a request that failed validation.
   *
   * @return the message
   */
  public String validationError() {
    return render(ProblemType.VALIDATION_ERROR.code(), Map.of());
  }

  /**
   * Detail of an account without limits.
   *
   * @param accountId account identifier
   * @return the message
   */
  public String accountLimitsNotFound(String accountId) {
    return render(ProblemType.ACCOUNT_LIMITS_NOT_FOUND.code(), Map.of("accountId", accountId));
  }

  /**
   * Detail of a channel without limit.
   *
   * @param accountId account identifier
   * @param channel channel without a configured limit
   * @return the message
   */
  public String limitNotFound(String accountId, Channel channel) {
    return render(
        ProblemType.LIMIT_NOT_FOUND.code(),
        Map.of("accountId", accountId, "channel", channel.name()));
  }

  /**
   * Detail of a reused transfer id.
   *
   * @param transferId reused idempotency key
   * @return the message
   */
  public String transferIdConflict(UUID transferId) {
    return render(
        ProblemType.TRANSFER_ID_CONFLICT.code(), Map.of("transferId", transferId.toString()));
  }

  /**
   * Detail of an invalid limit configuration. When both limits are known and the per-operation
   * limit exceeds the daily one, the message shows both amounts; otherwise a generic explanation is
   * returned.
   *
   * @param channel channel being configured
   * @param dailyLimit requested daily limit, may be null
   * @param perOperationLimit requested per-operation limit, may be null
   * @param currency currency of the limits
   * @return the message
   */
  public String invalidLimitConfiguration(
      Channel channel, BigDecimal dailyLimit, BigDecimal perOperationLimit, String currency) {
    if (dailyLimit != null
        && perOperationLimit != null
        && perOperationLimit.compareTo(dailyLimit) > 0) {
      return render(
          ProblemType.INVALID_LIMIT_CONFIGURATION.code(),
          Map.of(
              "dailyLimit", format(dailyLimit),
              "perOperationLimit", format(perOperationLimit),
              "currency", currency));
    }
    return render(INVALID_LIMIT_CONFIGURATION_GENERIC, Map.of("channel", String.valueOf(channel)));
  }

  /**
   * Detail of a concurrent modification conflict.
   *
   * @return the message
   */
  public String concurrentUpdate() {
    return render(ProblemType.CONCURRENT_UPDATE.code(), Map.of());
  }

  /**
   * Generic detail of an unexpected error; never includes internal information.
   *
   * @return the message
   */
  public String internalError() {
    return render(ProblemType.INTERNAL_ERROR.code(), Map.of());
  }

  /**
   * Formats an amount with the {@code es-MX} locale, thousands separator and 2 decimals.
   *
   * @param amount amount to format
   * @return the formatted amount, e.g. {@code 25,000.00}
   */
  public static String format(BigDecimal amount) {
    DecimalFormat decimalFormat =
        new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(AMOUNT_LOCALE));
    decimalFormat.setRoundingMode(RoundingMode.HALF_EVEN);
    return decimalFormat.format(amount);
  }

  private String render(String key, Map<String, String> values) {
    StringSubstitutor substitutor = new StringSubstitutor(values);
    substitutor.setEnableUndefinedVariableException(true);
    substitutor.setDisableSubstitutionInValues(true);
    return substitutor.replace(templates.get(key));
  }
}
