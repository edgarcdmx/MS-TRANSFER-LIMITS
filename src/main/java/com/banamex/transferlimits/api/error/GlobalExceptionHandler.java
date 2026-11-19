package com.banamex.transferlimits.api.error;

import com.banamex.transferlimits.api.dto.FieldError;
import com.banamex.transferlimits.config.observability.TraceIdProvider;
import com.banamex.transferlimits.domain.Amounts;
import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.exception.AccountLimitsNotFoundException;
import com.banamex.transferlimits.domain.exception.InvalidLimitConfigurationException;
import com.banamex.transferlimits.domain.exception.LimitNotFoundException;
import com.banamex.transferlimits.domain.exception.TransferIdConflictException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Translates every error into an RFC 9457 {@code application/problem+json} response.
 *
 * <p>Each problem carries {@code type}, {@code title}, {@code status}, {@code detail}, {@code
 * instance} and the extensions {@code code}, {@code traceId} (when a span is active) and, for
 * validation errors, {@code errors}. Transfer rejections add {@code reasonCode} and {@code
 * transferId}. Unexpected errors are logged and answered with a generic 500 that discloses no
 * internal information.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  /** Name of the {@code code} extension. */
  public static final String CODE = "code";

  /** Name of the {@code traceId} extension. */
  public static final String TRACE_ID = "traceId";

  /** Name of the {@code errors} extension. */
  public static final String ERRORS = "errors";

  /** Name of the {@code transferId} extension. */
  public static final String TRANSFER_ID = "transferId";

  /** Name of the {@code reasonCode} extension. */
  public static final String REASON_CODE = "reasonCode";

  static final String BODY_FIELD = "body";
  static final String INVALID_VALUE = "valor inválido o con formato incorrecto";
  static final String UNKNOWN_PROPERTY = "propiedad no permitida";
  static final String UNREADABLE_BODY = "el cuerpo de la solicitud falta o no es JSON válido";

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final RejectionMessageFactory messages;
  private final TraceIdProvider traceIdProvider;

  /**
   * Creates the handler.
   *
   * @param messages builder of the {@code detail} texts
   * @param traceIdProvider source of the current trace id
   */
  public GlobalExceptionHandler(RejectionMessageFactory messages, TraceIdProvider traceIdProvider) {
    this.messages = messages;
    this.traceIdProvider = traceIdProvider;
  }

  /**
   * 422 for a rejected transfer, with {@code reasonCode} and {@code transferId}.
   *
   * @param ex rejection
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler(TransferRejectedException.class)
  public ResponseEntity<Object> handleTransferRejected(
      TransferRejectedException ex, WebRequest request) {
    TransferValidation validation = ex.validation();
    ProblemType type = ProblemType.of(validation.reason());
    ProblemDetail problem = problem(type, messages.rejection(validation), request);
    problem.setProperty(TRANSFER_ID, validation.transferId());
    problem.setProperty(REASON_CODE, validation.reason().name());
    return respond(problem);
  }

  /**
   * 404 for an account without limits.
   *
   * @param ex domain exception
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler(AccountLimitsNotFoundException.class)
  public ResponseEntity<Object> handleAccountLimitsNotFound(
      AccountLimitsNotFoundException ex, WebRequest request) {
    return respond(
        problem(
            ProblemType.ACCOUNT_LIMITS_NOT_FOUND,
            messages.accountLimitsNotFound(ex.accountId()),
            request));
  }

  /**
   * 404 for a channel without limit.
   *
   * @param ex domain exception
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler(LimitNotFoundException.class)
  public ResponseEntity<Object> handleLimitNotFound(LimitNotFoundException ex, WebRequest request) {
    return respond(
        problem(
            ProblemType.LIMIT_NOT_FOUND,
            messages.limitNotFound(ex.accountId(), ex.channel()),
            request));
  }

  /**
   * 409 for a transfer id reused with another payload.
   *
   * @param ex domain exception
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler(TransferIdConflictException.class)
  public ResponseEntity<Object> handleTransferIdConflict(
      TransferIdConflictException ex, WebRequest request) {
    ProblemDetail problem =
        problem(
            ProblemType.TRANSFER_ID_CONFLICT,
            messages.transferIdConflict(ex.transferId()),
            request);
    problem.setProperty(TRANSFER_ID, ex.transferId());
    return respond(problem);
  }

  /**
   * 422 for limits that violate their invariants ({@code perOperationLimit > dailyLimit}).
   *
   * @param ex domain exception
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler(InvalidLimitConfigurationException.class)
  public ResponseEntity<Object> handleInvalidLimitConfiguration(
      InvalidLimitConfigurationException ex, WebRequest request) {
    return respond(
        problem(
            ProblemType.INVALID_LIMIT_CONFIGURATION,
            messages.invalidLimitConfiguration(
                ex.channel(), ex.dailyLimit(), ex.perOperationLimit(), Amounts.DEFAULT_CURRENCY),
            request));
  }

  /**
   * 409 when a concurrent request modified the same resource (optimistic lock or unique key).
   *
   * @param ex persistence exception
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler({
    OptimisticLockingFailureException.class,
    DataIntegrityViolationException.class
  })
  public ResponseEntity<Object> handleConcurrentUpdate(RuntimeException ex, WebRequest request) {
    LOG.warn("Concurrent update conflict: {}", ex.getClass().getName());
    return respond(problem(ProblemType.CONCURRENT_UPDATE, messages.concurrentUpdate(), request));
  }

  /**
   * 400 for constraint violations raised outside Spring MVC method validation.
   *
   * @param ex violations
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Object> handleConstraintViolation(
      ConstraintViolationException ex, WebRequest request) {
    List<FieldError> errors = new ArrayList<>();
    for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
      errors.add(
          new FieldError(lastNode(violation.getPropertyPath().toString()), violation.getMessage()));
    }
    return respond(validationProblem(errors, request));
  }

  /**
   * 400 for invalid arguments rejected by domain constructors.
   *
   * @param ex invalid argument
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Object> handleIllegalArgument(
      IllegalArgumentException ex, WebRequest request) {
    LOG.debug("Invalid argument", ex);
    return respond(validationProblem(List.of(), request));
  }

  /**
   * 500 for any other error. The exception is logged; the response is generic.
   *
   * @param ex unexpected error
   * @param request current request
   * @return the problem response
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
    LOG.error("Unexpected error", ex);
    return respond(problem(ProblemType.INTERNAL_ERROR, messages.internalError(), request));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors = new ArrayList<>();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(error -> errors.add(new FieldError(error.getField(), error.getDefaultMessage())));
    ex.getBindingResult()
        .getGlobalErrors()
        .forEach(
            error -> errors.add(new FieldError(error.getObjectName(), error.getDefaultMessage())));
    return respond(validationProblem(errors, request));
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors = new ArrayList<>();
    for (ParameterValidationResult result : ex.getParameterValidationResults()) {
      if (result instanceof ParameterErrors parameterErrors) {
        parameterErrors
            .getFieldErrors()
            .forEach(
                error -> errors.add(new FieldError(error.getField(), error.getDefaultMessage())));
      } else {
        String name = result.getMethodParameter().getParameterName();
        for (MessageSourceResolvable error : result.getResolvableErrors()) {
          errors.add(new FieldError(name, error.getDefaultMessage()));
        }
      }
    }
    return respond(validationProblem(errors, request));
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String field = Objects.requireNonNullElse(ex.getPropertyName(), BODY_FIELD);
    return respond(validationProblem(List.of(new FieldError(field, INVALID_VALUE)), request));
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    FieldError error = new FieldError(BODY_FIELD, UNREADABLE_BODY);
    if (ex.getCause() instanceof JacksonException jacksonException) {
      String field =
          jacksonException.getPath().stream()
              .map(JacksonException.Reference::getPropertyName)
              .filter(Objects::nonNull)
              .reduce((parent, child) -> parent + "." + child)
              .orElse(null);
      if (field != null) {
        error =
            new FieldError(
                field,
                jacksonException instanceof UnrecognizedPropertyException
                    ? UNKNOWN_PROPERTY
                    : INVALID_VALUE);
      }
    }
    return respond(validationProblem(List.of(error), request));
  }

  /**
   * Completes problems produced by Spring MVC for its own exceptions (405, 406, 415, 404 of unknown
   * resources...) with {@code code}, {@code instance} and {@code traceId}.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex,
      Object body,
      HttpHeaders headers,
      HttpStatusCode statusCode,
      WebRequest request) {
    ResponseEntity<Object> response =
        super.handleExceptionInternal(ex, body, headers, statusCode, request);
    if (response != null && response.getBody() instanceof ProblemDetail problem) {
      HttpStatus resolved = HttpStatus.resolve(statusCode.value());
      problem.setProperty(CODE, resolved == null ? "HTTP_" + statusCode.value() : resolved.name());
      decorate(problem, request);
      return ResponseEntity.status(response.getStatusCode())
          .headers(response.getHeaders())
          .contentType(MediaType.APPLICATION_PROBLEM_JSON)
          .body(problem);
    }
    return response;
  }

  private ProblemDetail validationProblem(List<FieldError> errors, WebRequest request) {
    ProblemDetail problem =
        problem(ProblemType.VALIDATION_ERROR, messages.validationError(), request);
    problem.setProperty(
        ERRORS,
        errors.stream()
            .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
            .toList());
    return problem;
  }

  private ProblemDetail problem(ProblemType type, String detail, WebRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
    problem.setType(type.type());
    problem.setTitle(type.title());
    problem.setProperty(CODE, type.code());
    decorate(problem, request);
    return problem;
  }

  private void decorate(ProblemDetail problem, WebRequest request) {
    problem.setInstance(URI.create(((ServletWebRequest) request).getRequest().getRequestURI()));
    traceIdProvider.currentTraceId().ifPresent(traceId -> problem.setProperty(TRACE_ID, traceId));
  }

  private static ResponseEntity<Object> respond(ProblemDetail problem) {
    return ResponseEntity.status(problem.getStatus())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }

  private static String lastNode(String propertyPath) {
    return propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
  }
}
