package com.banamex.transferlimits.api;

import static com.banamex.transferlimits.api.ApiTestData.ACCOUNT_ID;
import static com.banamex.transferlimits.api.ApiTestData.BUSINESS_DATE;
import static com.banamex.transferlimits.api.ApiTestData.TRACE_ID;
import static com.banamex.transferlimits.api.ApiTestData.amount;
import static com.banamex.transferlimits.api.ApiTestData.usage;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banamex.transferlimits.api.error.RejectionMessageFactory;
import com.banamex.transferlimits.application.GetAccountLimitsUseCase;
import com.banamex.transferlimits.application.UpdateChannelLimitUseCase;
import com.banamex.transferlimits.config.observability.TraceIdProvider;
import com.banamex.transferlimits.domain.AccountLimits;
import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.exception.AccountLimitsNotFoundException;
import com.banamex.transferlimits.domain.exception.InvalidLimitConfigurationException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountLimitsController.class)
@Import(RejectionMessageFactory.class)
class AccountLimitsControllerTest {

  private static final String LIMITS_URL = "/api/v1/accounts/{accountId}/limits";
  private static final String CHANNEL_URL = LIMITS_URL + "/{channelId}";
  private static final String VALID_BODY =
      """
      {"dailyLimit": 50000.00, "perOperationLimit": 20000.00, "currency": "MXN"}
      """;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private GetAccountLimitsUseCase getAccountLimitsUseCase;

  @MockitoBean private UpdateChannelLimitUseCase updateChannelLimitUseCase;

  @MockitoBean private TraceIdProvider traceIdProvider;

  @BeforeEach
  void setUp() {
    given(traceIdProvider.currentTraceId()).willReturn(Optional.of(TRACE_ID));
  }

  @Test
  void getReturnsLimitsAndConsumptionOfEveryChannel() throws Exception {
    given(getAccountLimitsUseCase.execute(ACCOUNT_ID))
        .willReturn(
            new AccountLimits(
                ACCOUNT_ID,
                BUSINESS_DATE,
                List.of(
                    usage(Channel.MOBILE_APP, "50000.00", "20000.00", "1500.00"),
                    usage(Channel.WEB, "100000.00", "50000.00", "0.00"))));

    mockMvc
        .perform(get(LIMITS_URL, ACCOUNT_ID))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
        .andExpect(jsonPath("$.businessDate").value("2026-11-19"))
        .andExpect(jsonPath("$.channels", hasSize(2)))
        .andExpect(jsonPath("$.channels[0].channel").value("MOBILE_APP"))
        .andExpect(jsonPath("$.channels[0].currency").value("MXN"))
        .andExpect(jsonPath("$.channels[0].dailyLimit").value(50000.00))
        .andExpect(jsonPath("$.channels[0].perOperationLimit").value(20000.00))
        .andExpect(jsonPath("$.channels[0].consumedToday").value(1500.00))
        .andExpect(jsonPath("$.channels[0].availableToday").value(48500.00))
        .andExpect(jsonPath("$.channels[0].updatedAt").value("2026-11-18T15:04:05Z"))
        .andExpect(jsonPath("$.channels[1].channel").value("WEB"))
        .andExpect(jsonPath("$.channels[1].availableToday").value(100000.00))
        .andExpect(content().string(containsString("\"consumedToday\":1500.00")));
  }

  @Test
  void getUnknownAccountIsNotFoundProblem() throws Exception {
    given(getAccountLimitsUseCase.execute(ACCOUNT_ID))
        .willThrow(new AccountLimitsNotFoundException(ACCOUNT_ID));

    mockMvc
        .perform(get(LIMITS_URL, ACCOUNT_ID))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type")
                .value("https://api.banamex.example/problems/account-limits-not-found"))
        .andExpect(jsonPath("$.title").value("Límites no encontrados"))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(
            jsonPath("$.detail").value("La cuenta 0012345678 no tiene límites configurados."))
        .andExpect(jsonPath("$.instance").value("/api/v1/accounts/0012345678/limits"))
        .andExpect(jsonPath("$.code").value("ACCOUNT_LIMITS_NOT_FOUND"))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID));
  }

  @Test
  void getWithInvalidAccountIdIsValidationProblem() throws Exception {
    mockMvc
        .perform(get(LIMITS_URL, "bad_account!"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://api.banamex.example/problems/validation-error"))
        .andExpect(jsonPath("$.title").value("Solicitud inválida"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.detail").value("La solicitud contiene campos inválidos."))
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].field").value("accountId"))
        .andExpect(
            jsonPath("$.errors[0].message")
                .value("debe contener sólo letras, dígitos o guiones (1 a 34 caracteres)"));
    verifyNoInteractions(getAccountLimitsUseCase);
  }

  @Test
  void getWithTooLongAccountIdIsValidationProblem() throws Exception {
    mockMvc
        .perform(get(LIMITS_URL, "A".repeat(35)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("accountId"));
  }

  @Test
  void putUpsertsTheChannelLimit() throws Exception {
    given(
            updateChannelLimitUseCase.execute(
                ACCOUNT_ID, Channel.MOBILE_APP, amount("50000.00"), amount("20000.00"), "MXN"))
        .willReturn(usage(Channel.MOBILE_APP, "50000.00", "20000.00", "1500.00"));

    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.channel").value("MOBILE_APP"))
        .andExpect(jsonPath("$.currency").value("MXN"))
        .andExpect(jsonPath("$.dailyLimit").value(50000.00))
        .andExpect(jsonPath("$.perOperationLimit").value(20000.00))
        .andExpect(jsonPath("$.consumedToday").value(1500.00))
        .andExpect(jsonPath("$.availableToday").value(48500.00))
        .andExpect(jsonPath("$.updatedAt").value("2026-11-18T15:04:05Z"));
  }

  @Test
  void putWithoutCurrencyPassesNullToTheUseCase() throws Exception {
    given(
            updateChannelLimitUseCase.execute(
                ACCOUNT_ID, Channel.WEB, amount("100"), amount("10"), null))
        .willReturn(usage(Channel.WEB, "100.00", "10.00", "0.00"));

    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "WEB")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyLimit\": 100, \"perOperationLimit\": 10}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.channel").value("WEB"));
    verify(updateChannelLimitUseCase)
        .execute(ACCOUNT_ID, Channel.WEB, amount("100"), amount("10"), null);
  }

  @Test
  void putWithInvalidBodyListsEveryFieldError() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"dailyLimit\": -1, \"perOperationLimit\": 10.001, \"currency\": \"USD\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID))
        .andExpect(jsonPath("$.errors", hasSize(3)))
        .andExpect(jsonPath("$.errors[0].field").value("currency"))
        .andExpect(jsonPath("$.errors[0].message").value("sólo se admite MXN"))
        .andExpect(jsonPath("$.errors[1].field").value("dailyLimit"))
        .andExpect(jsonPath("$.errors[1].message").value("debe ser mayor o igual a 0"))
        .andExpect(jsonPath("$.errors[2].field").value("perOperationLimit"))
        .andExpect(
            jsonPath("$.errors[2].message").value("debe tener máximo 17 enteros y 2 decimales"));
    verifyNoInteractions(updateChannelLimitUseCase);
  }

  @Test
  void putWithInvalidAccountAndBodyReportsBoth() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, "bad_account", "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"perOperationLimit\": 10}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors", hasSize(2)))
        .andExpect(jsonPath("$.errors[0].field").value("accountId"))
        .andExpect(jsonPath("$.errors[1].field").value("dailyLimit"))
        .andExpect(jsonPath("$.errors[1].message").value("no debe ser nulo"));
  }

  @Test
  void putWithUnknownChannelIsValidationProblem() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "ATM")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("channelId"))
        .andExpect(
            jsonPath("$.errors[0].message").value("valor inválido o con formato incorrecto"));
  }

  @Test
  void putWithUnknownPropertyIsRejected() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyLimit\": 10, \"perOperationLimit\": 1, \"extra\": true}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors[0].field").value("extra"))
        .andExpect(jsonPath("$.errors[0].message").value("propiedad no permitida"));
  }

  @Test
  void putWithMalformedJsonIsValidationProblem() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyLimit\": "))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("body"))
        .andExpect(
            jsonPath("$.errors[0].message")
                .value("el cuerpo de la solicitud falta o no es JSON válido"));
  }

  @Test
  void putWithoutBodyIsValidationProblem() throws Exception {
    mockMvc
        .perform(put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("body"));
  }

  @Test
  void putWithWrongTypeIsValidationProblemOnThatField() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyLimit\": \"mucho\", \"perOperationLimit\": 1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("dailyLimit"))
        .andExpect(
            jsonPath("$.errors[0].message").value("valor inválido o con formato incorrecto"));
  }

  @Test
  void putWithPerOperationAboveDailyIsUnprocessable() throws Exception {
    given(updateChannelLimitUseCase.execute(anyString(), any(Channel.class), any(), any(), any()))
        .willThrow(
            new InvalidLimitConfigurationException(
                "perOperationLimit must be less than or equal to dailyLimit",
                ACCOUNT_ID,
                Channel.MOBILE_APP,
                amount("50000.00"),
                amount("60000.00")));

    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dailyLimit\": 50000.00, \"perOperationLimit\": 60000.00}"))
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type")
                .value("https://api.banamex.example/problems/invalid-limit-configuration"))
        .andExpect(jsonPath("$.title").value("Configuración de límite inválida"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "El límite por operación (60,000.00 MXN) no puede ser mayor que el límite"
                        + " diario (50,000.00 MXN)."))
        .andExpect(jsonPath("$.instance").value("/api/v1/accounts/0012345678/limits/MOBILE_APP"))
        .andExpect(jsonPath("$.code").value("INVALID_LIMIT_CONFIGURATION"));
  }

  @Test
  void putLosingAnOptimisticLockIsConflict() throws Exception {
    given(updateChannelLimitUseCase.execute(anyString(), any(Channel.class), any(), any(), any()))
        .willThrow(new ObjectOptimisticLockingFailureException(Object.class, "id"));

    assertConcurrentUpdate();
  }

  @Test
  void putLosingAnOptimisticLockWithThePlainSpringExceptionIsConflict() throws Exception {
    given(updateChannelLimitUseCase.execute(anyString(), any(Channel.class), any(), any(), any()))
        .willThrow(new OptimisticLockingFailureException("row secret was updated"));

    assertConcurrentUpdate();
  }

  @Test
  void putLosingAnInsertRaceIsConflict() throws Exception {
    given(updateChannelLimitUseCase.execute(anyString(), any(Channel.class), any(), any(), any()))
        .willThrow(
            new DataIntegrityViolationException(
                "could not execute statement",
                new IllegalStateException(
                    "wrapper", new SQLException("duplicate key value pk_secret_table", "23505"))));

    assertConcurrentUpdate();
  }

  @Test
  void putWithDuplicateKeyExceptionIsConflict() throws Exception {
    given(updateChannelLimitUseCase.execute(anyString(), any(Channel.class), any(), any(), any()))
        .willThrow(new DuplicateKeyException("duplicate key value pk_secret_table"));

    assertConcurrentUpdate();
  }

  @Test
  void putWithNonUniqueIntegrityViolationIsInternalError() throws Exception {
    given(updateChannelLimitUseCase.execute(anyString(), any(Channel.class), any(), any(), any()))
        .willThrow(
            new DataIntegrityViolationException(
                "null value in column secret_column",
                new SQLException("null value in column secret_column", "23502")));

    assertIntegrityViolationIsInternalError();
  }

  @Test
  void putWithIntegrityViolationWithoutSqlStateIsInternalError() throws Exception {
    given(updateChannelLimitUseCase.execute(anyString(), any(Channel.class), any(), any(), any()))
        .willThrow(new DataIntegrityViolationException("secret constraint broken"));

    assertIntegrityViolationIsInternalError();
  }

  private void assertIntegrityViolationIsInternalError() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://api.banamex.example/problems/internal-error"))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(content().string(not(containsString("secret"))))
        .andExpect(content().string(not(containsString("23502"))));
  }

  @Test
  void unexpectedErrorIsGenericInternalErrorWithoutLeakingDetails() throws Exception {
    given(getAccountLimitsUseCase.execute(ACCOUNT_ID))
        .willThrow(new IllegalStateException("password=s3cr3t at jdbc:postgresql://db"));

    mockMvc
        .perform(get(LIMITS_URL, ACCOUNT_ID))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://api.banamex.example/problems/internal-error"))
        .andExpect(jsonPath("$.title").value("Error interno"))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(
            jsonPath("$.detail")
                .value("Ocurrió un error interno inesperado. Use el traceId para reportarlo."))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID))
        .andExpect(content().string(not(containsString("s3cr3t"))))
        .andExpect(content().string(not(containsString("IllegalStateException"))));
  }

  @Test
  void illegalArgumentFromTheDomainIsValidationProblem() throws Exception {
    given(getAccountLimitsUseCase.execute(ACCOUNT_ID))
        .willThrow(new IllegalArgumentException("consumedToday must not be negative"));

    mockMvc
        .perform(get(LIMITS_URL, ACCOUNT_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.errors", hasSize(0)))
        .andExpect(content().string(not(containsString("consumedToday must"))));
  }

  @Test
  void unsupportedMethodIsProblemWithStatusCode() throws Exception {
    mockMvc
        .perform(delete(LIMITS_URL, ACCOUNT_ID))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().exists("Allow"))
        .andExpect(jsonPath("$.status").value(405))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        .andExpect(jsonPath("$.instance").value("/api/v1/accounts/0012345678/limits"))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID));
  }

  @Test
  void unsupportedMediaTypeIsProblem() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.TEXT_PLAIN)
                .content("dailyLimit=1"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void problemOmitsTraceIdWithoutActiveSpan() throws Exception {
    given(traceIdProvider.currentTraceId()).willReturn(Optional.empty());
    given(getAccountLimitsUseCase.execute(ACCOUNT_ID))
        .willThrow(new AccountLimitsNotFoundException(ACCOUNT_ID));

    mockMvc
        .perform(get(LIMITS_URL, ACCOUNT_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ACCOUNT_LIMITS_NOT_FOUND"))
        .andExpect(jsonPath("$.traceId").doesNotExist());
  }

  private void assertConcurrentUpdate() throws Exception {
    mockMvc
        .perform(
            put(CHANNEL_URL, ACCOUNT_ID, "MOBILE_APP")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://api.banamex.example/problems/concurrent-update"))
        .andExpect(jsonPath("$.title").value("Actualización concurrente"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("CONCURRENT_UPDATE"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "El recurso fue modificado por otra solicitud concurrente; vuelva a"
                        + " intentarlo."))
        .andExpect(content().string(not(containsString("secret"))));
  }
}
