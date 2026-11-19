package com.banamex.transferlimits.application;

import static com.banamex.transferlimits.application.TestFixtures.ACCOUNT;
import static com.banamex.transferlimits.application.TestFixtures.BUSINESS_DATE;
import static com.banamex.transferlimits.application.TestFixtures.NOW;
import static com.banamex.transferlimits.application.TestFixtures.limit;
import static com.banamex.transferlimits.application.TestFixtures.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.banamex.transferlimits.application.TestFixtures.CountingTransactionOperations;
import com.banamex.transferlimits.application.TestFixtures.RecordingObservationHandler;
import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.RejectionReason;
import com.banamex.transferlimits.domain.TransferRequest;
import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.ValidationStatus;
import com.banamex.transferlimits.domain.exception.LimitNotFoundException;
import com.banamex.transferlimits.domain.exception.TransferIdConflictException;
import com.banamex.transferlimits.domain.port.ConsumptionRepository;
import com.banamex.transferlimits.domain.port.LimitRepository;
import com.banamex.transferlimits.domain.port.TransferValidationRepository;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

class ValidateTransferUseCaseTest {

  private static final UUID TRANSFER_ID = UUID.fromString("7d1b0f7e-3c2a-4a55-9a51-1c2b3d4e5f60");

  private LimitRepository limitRepository;
  private ConsumptionRepository consumptionRepository;
  private TransferValidationRepository validationRepository;
  private CountingTransactionOperations transactions;
  private RecordingObservationHandler observations;
  private ValidateTransferUseCase useCase;

  @BeforeEach
  void setUp() {
    limitRepository = mock(LimitRepository.class);
    consumptionRepository = mock(ConsumptionRepository.class);
    validationRepository = mock(TransferValidationRepository.class);
    transactions = new CountingTransactionOperations();
    observations = new RecordingObservationHandler();
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(observations);
    useCase =
        new ValidateTransferUseCase(
            limitRepository,
            consumptionRepository,
            validationRepository,
            TestFixtures.fixedDateProvider(),
            transactions,
            registry);
    when(validationRepository.find(TRANSFER_ID)).thenReturn(Optional.empty());
    when(validationRepository.saveIfAbsent(any())).thenReturn(true);
    when(limitRepository.find(ACCOUNT, Channel.WEB))
        .thenReturn(Optional.of(limit(Channel.WEB, "5000", "1000")));
  }

  private static TransferValidation stored(String amount) {
    return new TransferValidation(
        TRANSFER_ID,
        ACCOUNT,
        Channel.WEB,
        new BigDecimal(amount),
        "MXN",
        ValidationStatus.APPROVED,
        null,
        new BigDecimal("5000.00"),
        new BigDecimal("1000.00"),
        new BigDecimal("800.00"),
        new BigDecimal("4200.00"),
        BUSINESS_DATE,
        NOW.minusSeconds(60),
        false);
  }

  private String lowKey(String key) {
    KeyValue keyValue = observations.last().getLowCardinalityKeyValue(key);
    return keyValue == null ? null : keyValue.getValue();
  }

  @Test
  void approvesAndConsumesWhenWithinLimits() {
    when(consumptionRepository.tryConsume(
            ACCOUNT,
            Channel.WEB,
            BUSINESS_DATE,
            new BigDecimal("1000.00"),
            new BigDecimal("5000.00")))
        .thenReturn(Optional.of(new BigDecimal("1500.00")));

    TransferValidation result = useCase.execute(request(TRANSFER_ID, Channel.WEB, "1000"));

    assertThat(result.status()).isEqualTo(ValidationStatus.APPROVED);
    assertThat(result.approved()).isTrue();
    assertThat(result.reason()).isNull();
    assertThat(result.replayed()).isFalse();
    assertThat(result.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(result.accountId()).isEqualTo(ACCOUNT);
    assertThat(result.channel()).isEqualTo(Channel.WEB);
    assertThat(result.amount()).hasToString("1000.00");
    assertThat(result.currency()).isEqualTo("MXN");
    assertThat(result.dailyLimit()).hasToString("5000.00");
    assertThat(result.perOperationLimit()).hasToString("1000.00");
    assertThat(result.consumedToday()).hasToString("1500.00");
    assertThat(result.availableToday()).hasToString("3500.00");
    assertThat(result.businessDate()).isEqualTo(BUSINESS_DATE);
    assertThat(result.validatedAt()).isEqualTo(NOW);
    verify(validationRepository).saveIfAbsent(result);
    verify(consumptionRepository, never()).consumedOn(any(), any(), any());
    assertThat(transactions.transactions()).isEqualTo(1);
  }

  @Test
  void recordsTheObservationOfAnApproval() {
    when(consumptionRepository.tryConsume(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(new BigDecimal("100.00")));

    useCase.execute(request(TRANSFER_ID, Channel.WEB, "100"));

    assertThat(observations.stopped()).hasSize(1);
    Observation.Context context = observations.last();
    assertThat(context.getName()).isEqualTo(ValidateTransferUseCase.OBSERVATION_NAME);
    assertThat(context.getName()).isEqualTo("transfer.validation");
    assertThat(context.getContextualName()).isEqualTo("validate-transfer");
    assertThat(lowKey(ValidateTransferUseCase.CHANNEL_KEY)).isEqualTo("WEB");
    assertThat(lowKey(ValidateTransferUseCase.DECISION_KEY)).isEqualTo("APPROVED");
    assertThat(lowKey(ValidateTransferUseCase.REPLAYED_KEY)).isEqualTo("false");
    assertThat(context.getHighCardinalityKeyValue("transfer.id").getValue())
        .isEqualTo(TRANSFER_ID.toString());
    assertThat(context.getError()).isNull();
  }

  @Test
  void rejectsAboveThePerOperationLimitWithoutConsuming() {
    when(consumptionRepository.consumedOn(ACCOUNT, Channel.WEB, BUSINESS_DATE))
        .thenReturn(new BigDecimal("200.00"));

    TransferValidation result = useCase.execute(request(TRANSFER_ID, Channel.WEB, "1000.01"));

    assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
    assertThat(result.reason()).isEqualTo(RejectionReason.PER_OPERATION_LIMIT_EXCEEDED);
    assertThat(result.consumedToday()).hasToString("200.00");
    assertThat(result.availableToday()).hasToString("4800.00");
    verify(consumptionRepository, never()).tryConsume(any(), any(), any(), any(), any());
    verify(validationRepository).saveIfAbsent(result);
    assertThat(lowKey(ValidateTransferUseCase.DECISION_KEY)).isEqualTo("REJECTED");
    assertThat(lowKey(ValidateTransferUseCase.REPLAYED_KEY)).isEqualTo("false");
  }

  @Test
  void rejectsWhenTheAtomicConsumptionWouldExceedTheDailyLimit() {
    when(consumptionRepository.tryConsume(any(), any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(consumptionRepository.consumedOn(ACCOUNT, Channel.WEB, BUSINESS_DATE))
        .thenReturn(new BigDecimal("4500.00"));

    TransferValidation result = useCase.execute(request(TRANSFER_ID, Channel.WEB, "500.01"));

    assertThat(result.status()).isEqualTo(ValidationStatus.REJECTED);
    assertThat(result.reason()).isEqualTo(RejectionReason.DAILY_LIMIT_EXCEEDED);
    assertThat(result.consumedToday()).hasToString("4500.00");
    assertThat(result.availableToday()).hasToString("500.00");
    verify(validationRepository).saveIfAbsent(result);
    assertThat(lowKey(ValidateTransferUseCase.DECISION_KEY)).isEqualTo("REJECTED");
  }

  @Test
  void throwsWhenTheChannelHasNoLimit() {
    when(limitRepository.find(ACCOUNT, Channel.BRANCH)).thenReturn(Optional.empty());

    TransferRequest request = request(TRANSFER_ID, Channel.BRANCH, "10");
    assertThatThrownBy(() -> useCase.execute(request))
        .isInstanceOfSatisfying(
            LimitNotFoundException.class,
            exception -> {
              assertThat(exception.accountId()).isEqualTo(ACCOUNT);
              assertThat(exception.channel()).isEqualTo(Channel.BRANCH);
            });
    verifyNoInteractions(consumptionRepository);
    verify(validationRepository, never()).saveIfAbsent(any());
    assertThat(lowKey(ValidateTransferUseCase.DECISION_KEY)).isEqualTo("LIMIT_NOT_FOUND");
    assertThat(lowKey(ValidateTransferUseCase.CHANNEL_KEY)).isEqualTo("BRANCH");
    assertThat(observations.last().getError()).isInstanceOf(LimitNotFoundException.class);
  }

  @Test
  void replaysStoredValidationWithTheSamePayload() {
    TransferValidation stored = stored("300.00");
    when(validationRepository.find(TRANSFER_ID)).thenReturn(Optional.of(stored));

    TransferValidation result = useCase.execute(request(TRANSFER_ID, Channel.WEB, "300"));

    assertThat(result.replayed()).isTrue();
    assertThat(result).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(stored);
    verifyNoInteractions(limitRepository, consumptionRepository);
    verify(validationRepository, never()).saveIfAbsent(any());
    assertThat(lowKey(ValidateTransferUseCase.DECISION_KEY)).isEqualTo("APPROVED");
    assertThat(lowKey(ValidateTransferUseCase.REPLAYED_KEY)).isEqualTo("true");
  }

  @Test
  void conflictsWhenTheTransferIdIsReusedWithAnotherPayload() {
    when(validationRepository.find(TRANSFER_ID)).thenReturn(Optional.of(stored("300.00")));

    TransferRequest request = request(TRANSFER_ID, Channel.WEB, "300.01");
    assertThatThrownBy(() -> useCase.execute(request))
        .isInstanceOfSatisfying(
            TransferIdConflictException.class,
            exception -> {
              assertThat(exception.transferId()).isEqualTo(TRANSFER_ID);
              assertThat(exception.accountId()).isEqualTo(ACCOUNT);
            });
    verifyNoInteractions(limitRepository, consumptionRepository);
    assertThat(lowKey(ValidateTransferUseCase.DECISION_KEY)).isEqualTo("TRANSFER_ID_CONFLICT");
  }

  @Test
  void lostSameTransferIdRaceIsRetriedInNewTransactionAndReplayed() {
    TransferValidation winner = stored("100.00");
    when(validationRepository.find(TRANSFER_ID)).thenReturn(Optional.empty(), Optional.of(winner));
    when(validationRepository.saveIfAbsent(any())).thenReturn(false);
    when(consumptionRepository.tryConsume(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(new BigDecimal("900.00")));

    TransferValidation result = useCase.execute(request(TRANSFER_ID, Channel.WEB, "100"));

    assertThat(result.replayed()).isTrue();
    assertThat(result.consumedToday()).isEqualTo(winner.consumedToday());
    assertThat(result.validatedAt()).isEqualTo(winner.validatedAt());
    assertThat(transactions.transactions()).isEqualTo(2);
    verify(validationRepository, times(2)).find(TRANSFER_ID);
    verify(validationRepository, times(1)).saveIfAbsent(any());
    verify(consumptionRepository, times(1)).tryConsume(any(), any(), any(), any(), any());
    assertThat(lowKey(ValidateTransferUseCase.REPLAYED_KEY)).isEqualTo("true");
    assertThat(lowKey(ValidateTransferUseCase.DECISION_KEY)).isEqualTo("APPROVED");
  }

  @Test
  void lostRaceWithDifferentPayloadEndsInConflict() {
    when(validationRepository.find(TRANSFER_ID))
        .thenReturn(Optional.empty(), Optional.of(stored("999.00")));
    when(validationRepository.saveIfAbsent(any())).thenReturn(false);
    when(consumptionRepository.tryConsume(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(new BigDecimal("100.00")));

    TransferRequest request = request(TRANSFER_ID, Channel.WEB, "100");
    assertThatThrownBy(() -> useCase.execute(request))
        .isInstanceOf(TransferIdConflictException.class);
    assertThat(transactions.transactions()).isEqualTo(2);
  }

  @Test
  void givesUpWhenTheRaceIsLostOnEveryAttempt() {
    when(validationRepository.saveIfAbsent(any())).thenReturn(false);
    when(consumptionRepository.tryConsume(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(new BigDecimal("100.00")));

    TransferRequest request = request(TRANSFER_ID, Channel.WEB, "100");
    assertThatThrownBy(() -> useCase.execute(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TRANSFER_ID.toString())
        .hasMessageContaining("2 attempts")
        .hasCauseInstanceOf(RuntimeException.class);
    assertThat(transactions.transactions()).isEqualTo(2);
    assertThat(lowKey(ValidateTransferUseCase.DECISION_KEY)).isEqualTo("ERROR");
    assertThat(observations.last().getError()).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void savesTheValidationThatIsReturned() {
    when(consumptionRepository.tryConsume(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(new BigDecimal("5000.00")));

    TransferValidation result = useCase.execute(request(TRANSFER_ID, Channel.WEB, "1000"));

    ArgumentCaptor<TransferValidation> saved = ArgumentCaptor.forClass(TransferValidation.class);
    verify(validationRepository).saveIfAbsent(saved.capture());
    assertThat(saved.getValue()).isEqualTo(result);
    assertThat(result.availableToday()).hasToString("0.00");
  }

  @Test
  void worksWithoutObservationHandlersAndRealTransactionTemplateFallback() {
    ValidateTransferUseCase plain =
        new ValidateTransferUseCase(
            limitRepository,
            consumptionRepository,
            validationRepository,
            TestFixtures.fixedDateProvider(),
            TransactionOperations.withoutTransaction(),
            ObservationRegistry.NOOP);
    when(consumptionRepository.tryConsume(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(new BigDecimal("10.00")));

    TransferValidation result = plain.execute(request(TRANSFER_ID, Channel.WEB, "10"));

    assertThat(result.approved()).isTrue();
    assertThat(observations.stopped()).isEmpty();
  }
}
