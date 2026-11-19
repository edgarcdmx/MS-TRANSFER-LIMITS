package com.banamex.transferlimits.api;

import static com.banamex.transferlimits.api.ApiTestData.ACCOUNT_ID;
import static com.banamex.transferlimits.api.ApiTestData.TRACE_ID;
import static com.banamex.transferlimits.api.ApiTestData.TRANSFER_ID;
import static com.banamex.transferlimits.api.ApiTestData.amount;
import static com.banamex.transferlimits.api.ApiTestData.approved;
import static com.banamex.transferlimits.api.ApiTestData.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.banamex.transferlimits.api.error.RejectionMessageFactory;
import com.banamex.transferlimits.application.ValidateTransferUseCase;
import com.banamex.transferlimits.config.observability.TraceIdProvider;
import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.RejectionReason;
import com.banamex.transferlimits.domain.TransferRequest;
import com.banamex.transferlimits.domain.exception.LimitNotFoundException;
import com.banamex.transferlimits.domain.exception.TransferIdConflictException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(TransferValidationController.class)
@Import(RejectionMessageFactory.class)
class TransferValidationControllerTest {

  private static final String URL = "/api/v1/accounts/{accountId}/transfer-validations";
  private static final String INSTANCE = "/api/v1/accounts/0012345678/transfer-validations";
  private static final String VALID_BODY =
      """
      {
        "transferId": "3f1c2b9e-7a4d-4c1e-9b8a-2d6f5e4c3b2a",
        "channel": "MOBILE_APP",
        "amount": 1500.00,
        "currency": "MXN"
      }
      """;

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ValidateTransferUseCase validateTransferUseCase;

  @MockitoBean private TraceIdProvider traceIdProvider;

  @BeforeEach
  void setUp() {
    given(traceIdProvider.currentTraceId()).willReturn(Optional.of(TRACE_ID));
  }

  @Test
  void approvedTransferIsCreatedWithLocation() throws Exception {
    given(validateTransferUseCase.execute(any())).willReturn(approved(false));

    perform(VALID_BODY)
        .andExpect(status().isCreated())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(
            header()
                .string(
                    "Location",
                    "/api/v1/accounts/0012345678/transfer-validations/"
                        + "3f1c2b9e-7a4d-4c1e-9b8a-2d6f5e4c3b2a"))
        .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()))
        .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.channel").value("MOBILE_APP"))
        .andExpect(jsonPath("$.amount").value(1500.00))
        .andExpect(jsonPath("$.currency").value("MXN"))
        .andExpect(jsonPath("$.dailyLimit").value(50000.00))
        .andExpect(jsonPath("$.perOperationLimit").value(20000.00))
        .andExpect(jsonPath("$.consumedToday").value(1500.00))
        .andExpect(jsonPath("$.availableToday").value(48500.00))
        .andExpect(jsonPath("$.businessDate").value("2026-11-19"))
        .andExpect(jsonPath("$.validatedAt").value("2026-11-19T17:21:30.123Z"));

    ArgumentCaptor<TransferRequest> captor = ArgumentCaptor.forClass(TransferRequest.class);
    verify(validateTransferUseCase).execute(captor.capture());
    assertThat(captor.getValue())
        .isEqualTo(
            new TransferRequest(
                TRANSFER_ID, ACCOUNT_ID, Channel.MOBILE_APP, amount("1500.00"), "MXN"));
  }

  @Test
  void replayedApprovalIsOkWithoutLocation() throws Exception {
    given(validateTransferUseCase.execute(any())).willReturn(approved(true));

    perform(VALID_BODY)
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()));
  }

  @Test
  void perOperationRejectionIsUnprocessableProblem() throws Exception {
    given(validateTransferUseCase.execute(any()))
        .willReturn(rejected(RejectionReason.PER_OPERATION_LIMIT_EXCEEDED, "25000.00", "0", false));

    perform(VALID_BODY)
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type")
                .value("https://api.banamex.example/problems/per-operation-limit-exceeded"))
        .andExpect(jsonPath("$.title").value("Límite por operación excedido"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "El monto 25,000.00 MXN excede el límite por operación de 20,000.00 MXN"
                        + " para el canal MOBILE_APP."))
        .andExpect(jsonPath("$.instance").value(INSTANCE))
        .andExpect(jsonPath("$.code").value("PER_OPERATION_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.reasonCode").value("PER_OPERATION_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID));
  }

  @Test
  void dailyRejectionIsUnprocessableProblemEvenWhenReplayed() throws Exception {
    given(validateTransferUseCase.execute(any()))
        .willReturn(rejected(RejectionReason.DAILY_LIMIT_EXCEEDED, "15000.00", "41500.00", true));

    perform(VALID_BODY)
        .andExpect(status().isUnprocessableContent())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://api.banamex.example/problems/daily-limit-exceeded"))
        .andExpect(jsonPath("$.title").value("Límite diario excedido"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "El monto 15,000.00 MXN excede el disponible diario de 8,500.00 MXN (límite"
                        + " 50,000.00, consumido 41,500.00) para el canal MOBILE_APP."))
        .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.reasonCode").value("DAILY_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()));
  }

  @Test
  void channelWithoutLimitIsNotFoundProblem() throws Exception {
    given(validateTransferUseCase.execute(any()))
        .willThrow(new LimitNotFoundException(ACCOUNT_ID, Channel.BRANCH));

    perform(VALID_BODY)
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://api.banamex.example/problems/limit-not-found"))
        .andExpect(jsonPath("$.title").value("Límite no encontrado"))
        .andExpect(
            jsonPath("$.detail")
                .value("La cuenta 0012345678 no tiene límite configurado para el canal BRANCH."))
        .andExpect(jsonPath("$.instance").value(INSTANCE))
        .andExpect(jsonPath("$.code").value("LIMIT_NOT_FOUND"));
  }

  @Test
  void reusedTransferIdIsConflictProblem() throws Exception {
    given(validateTransferUseCase.execute(any()))
        .willThrow(new TransferIdConflictException(TRANSFER_ID, ACCOUNT_ID));

    perform(VALID_BODY)
        .andExpect(status().isConflict())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://api.banamex.example/problems/transfer-id-conflict"))
        .andExpect(jsonPath("$.title").value("Conflicto de transferId"))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "El transferId 3f1c2b9e-7a4d-4c1e-9b8a-2d6f5e4c3b2a ya fue utilizado con"
                        + " datos distintos."))
        .andExpect(jsonPath("$.code").value("TRANSFER_ID_CONFLICT"))
        .andExpect(jsonPath("$.transferId").value(TRANSFER_ID.toString()))
        .andExpect(jsonPath("$.traceId").value(TRACE_ID));
  }

  @Test
  void missingFieldsAreValidationErrors() throws Exception {
    perform("{}")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.instance").value(INSTANCE))
        .andExpect(jsonPath("$.errors", hasSize(4)))
        .andExpect(jsonPath("$.errors[0].field").value("amount"))
        .andExpect(jsonPath("$.errors[0].message").value("no debe ser nulo"))
        .andExpect(jsonPath("$.errors[1].field").value("channel"))
        .andExpect(jsonPath("$.errors[2].field").value("currency"))
        .andExpect(jsonPath("$.errors[3].field").value("transferId"));
    verifyNoInteractions(validateTransferUseCase);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1.00"})
  void nonPositiveAmountIsValidationError(String amountValue) throws Exception {
    perform(VALID_BODY.replace("1500.00", amountValue))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("amount"))
        .andExpect(jsonPath("$.errors[0].message").value("debe ser mayor que 0"));
  }

  @Test
  void amountWithMoreThanTwoDecimalsIsValidationError() throws Exception {
    perform(VALID_BODY.replace("1500.00", "1500.001"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("amount"))
        .andExpect(
            jsonPath("$.errors[0].message").value("debe tener máximo 17 enteros y 2 decimales"));
  }

  @Test
  void currencyOtherThanMxnIsValidationError() throws Exception {
    perform(VALID_BODY.replace("\"MXN\"", "\"USD\""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("currency"))
        .andExpect(jsonPath("$.errors[0].message").value("sólo se admite MXN"));
  }

  @Test
  void invalidUuidIsValidationErrorOnTransferId() throws Exception {
    perform(VALID_BODY.replace("3f1c2b9e-7a4d-4c1e-9b8a-2d6f5e4c3b2a", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("transferId"))
        .andExpect(
            jsonPath("$.errors[0].message").value("valor inválido o con formato incorrecto"));
  }

  @Test
  void unknownChannelIsValidationErrorOnChannel() throws Exception {
    perform(VALID_BODY.replace("MOBILE_APP", "ATM"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("channel"));
  }

  @Test
  void jsonArrayBodyIsValidationErrorOnBody() throws Exception {
    perform("[]")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("body"));
  }

  @Test
  void invalidAccountIdIsValidationError() throws Exception {
    mockMvc
        .perform(
            post(URL, "account with spaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors", hasSize(1)))
        .andExpect(jsonPath("$.errors[0].field").value("accountId"));
    verifyNoInteractions(validateTransferUseCase);
  }

  @Test
  void problemIsReturnedWhenClientOnlyAcceptsJson() throws Exception {
    given(validateTransferUseCase.execute(any()))
        .willThrow(new LimitNotFoundException(ACCOUNT_ID, Channel.BRANCH));

    mockMvc
        .perform(
            post(URL, ACCOUNT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LIMIT_NOT_FOUND"));
  }

  private ResultActions perform(String body) throws Exception {
    return mockMvc.perform(
        post(URL, ACCOUNT_ID).contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
