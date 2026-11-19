package com.banamex.transferlimits.api.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.banamex.transferlimits.api.dto.FieldError;
import com.banamex.transferlimits.api.dto.TransferValidationRequest;
import com.banamex.transferlimits.config.observability.TraceIdProvider;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.core.JacksonException;

/** Branches of {@link GlobalExceptionHandler} that the controllers cannot trigger. */
class GlobalExceptionHandlerTest {

  private static final String PATH = "/api/v1/accounts/0012345678/transfer-validations";

  private final TraceIdProvider traceIdProvider = mock(TraceIdProvider.class);
  private final GlobalExceptionHandler handler =
      new GlobalExceptionHandler(new RejectionMessageFactory(), traceIdProvider);
  private ValidatorFactory validatorFactory;
  private ServletWebRequest request;

  @BeforeEach
  void setUp() {
    given(traceIdProvider.currentTraceId()).willReturn(Optional.empty());
    validatorFactory = Validation.buildDefaultValidatorFactory();
    request = new ServletWebRequest(new MockHttpServletRequest("POST", PATH));
  }

  @AfterEach
  void tearDown() {
    validatorFactory.close();
  }

  @Test
  void constraintViolationsBecomeFieldErrors() {
    Validator validator = validatorFactory.getValidator();
    TransferValidationRequest invalid =
        new TransferValidationRequest(null, null, new BigDecimal("1.00"), "USD");

    ResponseEntity<Object> response =
        handler.handleConstraintViolation(
            new ConstraintViolationException(validator.validate(invalid)), request);

    ProblemDetail problem = assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    assertThat(problem.getProperties().get(GlobalExceptionHandler.ERRORS))
        .asInstanceOf(InstanceOfAssertFactories.LIST)
        .containsExactly(
            new FieldError("channel", "no debe ser nulo"),
            new FieldError("currency", "sólo se admite MXN"),
            new FieldError("transferId", "no debe ser nulo"));
  }

  @Test
  void methodArgumentNotValidListsFieldAndGlobalErrors() throws Exception {
    BeanPropertyBindingResult bindingResult =
        new BeanPropertyBindingResult(
            new TransferValidationRequest(null, null, BigDecimal.ONE, "MXN"), "request");
    bindingResult.rejectValue("transferId", "NotNull", "no debe ser nulo");
    bindingResult.reject("Consistency", "combinación inválida");
    MethodParameter parameter =
        new MethodParameter(
            GlobalExceptionHandlerTest.class.getDeclaredMethod(
                "target", TransferValidationRequest.class),
            0);

    ResponseEntity<Object> response =
        handler.handleMethodArgumentNotValid(
            new MethodArgumentNotValidException(parameter, bindingResult),
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            request);

    ProblemDetail problem = assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    assertThat(problem.getProperties().get(GlobalExceptionHandler.ERRORS))
        .asInstanceOf(InstanceOfAssertFactories.LIST)
        .containsExactly(
            new FieldError("request", "combinación inválida"),
            new FieldError("transferId", "no debe ser nulo"));
  }

  @Test
  void nestedJsonPathsAreJoinedWithDots() {
    JacksonException cause =
        JacksonException.wrapWithPath(new IllegalStateException("bad"), "child", "amount")
            .prependPath("root", "limits");

    ResponseEntity<Object> response =
        handler.handleHttpMessageNotReadable(
            new HttpMessageNotReadableException(
                "bad", cause, new MockHttpInputMessage(new byte[0])),
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            request);

    ProblemDetail problem = assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    assertThat(problem.getProperties().get(GlobalExceptionHandler.ERRORS))
        .asInstanceOf(InstanceOfAssertFactories.LIST)
        .containsExactly(new FieldError("limits.amount", GlobalExceptionHandler.INVALID_VALUE));
  }

  @Test
  void frameworkProblemWithNonStandardStatusGetsGenericCode() {
    given(traceIdProvider.currentTraceId()).willReturn(Optional.of("abc"));
    HttpStatusCode status = HttpStatusCode.valueOf(599);

    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            new IllegalStateException("boom"),
            ProblemDetail.forStatus(status),
            new HttpHeaders(),
            status,
            request);

    assertThat(response).isNotNull();
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail problem = (ProblemDetail) response.getBody();
    assertThat(problem.getProperties())
        .containsEntry(GlobalExceptionHandler.CODE, "HTTP_599")
        .containsEntry(GlobalExceptionHandler.TRACE_ID, "abc");
    assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
  }

  @Test
  void nonProblemBodiesAreLeftUntouched() {
    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            new IllegalStateException("boom"),
            "plain body",
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            request);

    assertThat(response).isNotNull();
    assertThat(response.getBody()).isEqualTo("plain body");
  }

  @Test
  void committedResponsesAreNotRewritten() {
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    servletResponse.setCommitted(true);
    ServletWebRequest committed =
        new ServletWebRequest(new MockHttpServletRequest("GET", PATH), servletResponse);

    ResponseEntity<Object> response =
        handler.handleExceptionInternal(
            new IllegalStateException("boom"),
            ProblemDetail.forStatus(HttpStatus.BAD_REQUEST),
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            committed);

    assertThat(response).isNull();
  }

  private static ProblemDetail assertProblem(
      ResponseEntity<Object> response, HttpStatus status, String code) {
    assertThat(response.getStatusCode()).isEqualTo(status);
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    ProblemDetail problem = (ProblemDetail) response.getBody();
    assertThat(problem.getType())
        .isEqualTo(URI.create("https://api.banamex.example/problems/validation-error"));
    assertThat(problem.getTitle()).isEqualTo("Solicitud inválida");
    assertThat(problem.getInstance()).isEqualTo(URI.create(PATH));
    assertThat(problem.getProperties())
        .containsEntry(GlobalExceptionHandler.CODE, code)
        .doesNotContainKey(GlobalExceptionHandler.TRACE_ID);
    return problem;
  }

  @SuppressWarnings("unused")
  private void target(TransferValidationRequest body) {
    // Method whose parameter is used to build a MethodArgumentNotValidException.
  }
}
