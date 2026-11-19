package com.banamex.transferlimits.api.error;

import com.banamex.transferlimits.domain.RejectionReason;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Catalogue of the RFC 9457 problem types exposed by the API (see the {@code ProblemDetail} schema
 * of {@code openapi.yaml}).
 *
 * <p>The {@code type} URI is {@code https://api.banamex.example/problems/<kebab-code>} and the
 * {@code code} extension is the constant name.
 */
public enum ProblemType {
  /** Invalid request: parameters, malformed body or Bean Validation violations. */
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Solicitud inválida"),
  /** The account has no limits configured. */
  ACCOUNT_LIMITS_NOT_FOUND(HttpStatus.NOT_FOUND, "Límites no encontrados"),
  /** The account has no limit configured for the channel. */
  LIMIT_NOT_FOUND(HttpStatus.NOT_FOUND, "Límite no encontrado"),
  /** The transfer id was already used with a different payload. */
  TRANSFER_ID_CONFLICT(HttpStatus.CONFLICT, "Conflicto de transferId"),
  /** A concurrent request modified the same resource. */
  CONCURRENT_UPDATE(HttpStatus.CONFLICT, "Actualización concurrente"),
  /** The per-operation limit is greater than the daily limit (or another invariant failed). */
  INVALID_LIMIT_CONFIGURATION(HttpStatus.UNPROCESSABLE_CONTENT, "Configuración de límite inválida"),
  /** Transfer rejected: amount greater than the per-operation limit. */
  PER_OPERATION_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Límite por operación excedido"),
  /** Transfer rejected: amount greater than what is available today. */
  DAILY_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Límite diario excedido"),
  /** Unexpected error; no internal details are disclosed. */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno");

  /** Base URI of every problem type. */
  public static final String BASE_URI = "https://api.banamex.example/problems/";

  private final HttpStatus status;
  private final String title;
  private final URI type;

  ProblemType(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
    this.type = URI.create(BASE_URI + name().toLowerCase(Locale.ROOT).replace('_', '-'));
  }

  /**
   * HTTP status of the problem.
   *
   * @return the status
   */
  public HttpStatus status() {
    return status;
  }

  /**
   * Short human readable summary (Spanish).
   *
   * @return the title
   */
  public String title() {
    return title;
  }

  /**
   * Problem type URI.
   *
   * @return the type URI
   */
  public URI type() {
    return type;
  }

  /**
   * Stable machine readable code ({@code code} extension).
   *
   * @return the code
   */
  public String code() {
    return name();
  }

  /**
   * Problem type of a transfer rejection.
   *
   * @param reason rejection reason
   * @return the problem type with the same code
   */
  public static ProblemType of(RejectionReason reason) {
    return valueOf(reason.name());
  }
}
