package com.banamex.transferlimits.application;

import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.ChannelUsage;
import com.banamex.transferlimits.domain.LimitPolicy;
import com.banamex.transferlimits.domain.RejectionReason;
import com.banamex.transferlimits.domain.TransferRequest;
import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.ValidationStatus;
import com.banamex.transferlimits.domain.exception.DomainException;
import com.banamex.transferlimits.domain.exception.LimitNotFoundException;
import com.banamex.transferlimits.domain.exception.TransferIdConflictException;
import com.banamex.transferlimits.domain.port.BusinessDateProvider;
import com.banamex.transferlimits.domain.port.ConsumptionRepository;
import com.banamex.transferlimits.domain.port.LimitRepository;
import com.banamex.transferlimits.domain.port.TransferValidationRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Validates a transfer against the channel limits and consumes the daily limit when approved.
 *
 * <p>Algorithm (one READ COMMITTED transaction per attempt, see ADR-002):
 *
 * <ol>
 *   <li>Idempotency: if {@code transferId} already exists, return it with {@code replayed = true}
 *       when the payload (account, channel, amount, currency) matches, otherwise throw {@link
 *       TransferIdConflictException}.
 *   <li>Load the channel limit, or throw {@link LimitNotFoundException}.
 *   <li>Per-operation rule ({@link LimitPolicy#checkPerOperation}); a rejection consumes nothing.
 *   <li>Atomic conditional consumption ({@link ConsumptionRepository#tryConsume}); empty means
 *       {@link RejectionReason#DAILY_LIMIT_EXCEEDED}.
 *   <li>Persist the validation (approved or rejected) with {@link
 *       TransferValidationRepository#saveIfAbsent}.
 * </ol>
 *
 * <p><b>Same {@code transferId} racing:</b> the validation row is inserted <em>after</em> consuming
 * but <em>in the same transaction</em>. If a concurrent request with the same {@code transferId}
 * committed first, {@code saveIfAbsent} returns {@code false}; this attempt then throws an internal
 * exception that rolls back the whole transaction, including its consumption, so the amount is
 * never counted twice. A second attempt in a fresh transaction finds the committed row and returns
 * it as a replay (or a conflict if the payload differs). Lock order is always consumption row then
 * validation row, so the two transactions cannot deadlock.
 *
 * <p>The execution is wrapped in the {@code transfer.validation} Micrometer observation (exported
 * as an OpenTelemetry span) with the low-cardinality keys {@code channel}, {@code decision}
 * (APPROVED, REJECTED or the error code) and {@code replayed}.
 */
@Service
public class ValidateTransferUseCase {

  /** Name of the observation (span / timer). */
  public static final String OBSERVATION_NAME = "transfer.validation";

  /** Low-cardinality key holding the channel. */
  public static final String CHANNEL_KEY = "channel";

  /** Low-cardinality key holding the decision. */
  public static final String DECISION_KEY = "decision";

  /** Low-cardinality key telling whether the result was an idempotent replay. */
  public static final String REPLAYED_KEY = "replayed";

  private static final String UNKNOWN_ERROR = "ERROR";
  private static final int MAX_ATTEMPTS = 2;

  private final LimitRepository limitRepository;
  private final ConsumptionRepository consumptionRepository;
  private final TransferValidationRepository validationRepository;
  private final BusinessDateProvider businessDateProvider;
  private final TransactionOperations transactionOperations;
  private final ObservationRegistry observationRegistry;

  /**
   * Creates the use case.
   *
   * @param limitRepository channel limits store
   * @param consumptionRepository daily consumption store (atomic)
   * @param validationRepository idempotency store
   * @param businessDateProvider clock and business date source
   * @param transactionOperations runs each attempt in its own transaction
   * @param observationRegistry registry for the {@code transfer.validation} observation
   */
  public ValidateTransferUseCase(
      LimitRepository limitRepository,
      ConsumptionRepository consumptionRepository,
      TransferValidationRepository validationRepository,
      BusinessDateProvider businessDateProvider,
      TransactionOperations transactionOperations,
      ObservationRegistry observationRegistry) {
    this.limitRepository = limitRepository;
    this.consumptionRepository = consumptionRepository;
    this.validationRepository = validationRepository;
    this.businessDateProvider = businessDateProvider;
    this.transactionOperations = transactionOperations;
    this.observationRegistry = observationRegistry;
  }

  /**
   * Validates the transfer and, if approved, consumes its amount from the daily limit.
   *
   * @param request transfer to validate
   * @return the validation; {@code replayed = true} if the transfer id was already validated
   * @throws LimitNotFoundException if the channel has no limit configured
   * @throws TransferIdConflictException if the transfer id was used with a different payload
   */
  public TransferValidation execute(TransferRequest request) {
    Observation observation =
        Observation.createNotStarted(OBSERVATION_NAME, observationRegistry)
            .contextualName("validate-transfer")
            .lowCardinalityKeyValue(CHANNEL_KEY, request.channel().name())
            .lowCardinalityKeyValue(DECISION_KEY, UNKNOWN_ERROR)
            .lowCardinalityKeyValue(REPLAYED_KEY, Boolean.FALSE.toString())
            .highCardinalityKeyValue("transfer.id", request.transferId().toString());
    return observation.observe(() -> {
      try {
        TransferValidation result = validateWithRetry(request);
        observation.lowCardinalityKeyValue(DECISION_KEY, result.status().name());
        observation.lowCardinalityKeyValue(REPLAYED_KEY, String.valueOf(result.replayed()));
        return result;
      } catch (DomainException e) {
        observation.lowCardinalityKeyValue(DECISION_KEY, e.code());
        throw e;
      }
    });
  }

  private TransferValidation validateWithRetry(TransferRequest request) {
    for (int attempt = 1; ; attempt++) {
      try {
        return transactionOperations.execute(status -> validateInTransaction(request));
      } catch (ConcurrentTransferIdException e) {
        if (attempt >= MAX_ATTEMPTS) {
          throw new IllegalStateException(
              "transferId " + request.transferId() + " kept racing after " + attempt + " attempts",
              e);
        }
      }
    }
  }

  private TransferValidation validateInTransaction(TransferRequest request) {
    Optional<TransferValidation> existing = validationRepository.find(request.transferId());
    if (existing.isPresent()) {
      return replay(existing.get(), request);
    }
    String accountId = request.accountId();
    ChannelLimit limit =
        limitRepository
            .find(accountId, request.channel())
            .orElseThrow(() -> new LimitNotFoundException(accountId, request.channel()));
    Instant now = businessDateProvider.now();
    LocalDate businessDate = businessDateProvider.businessDateOf(now);

    Optional<RejectionReason> rejection = LimitPolicy.checkPerOperation(limit, request.amount());
    BigDecimal consumedToday;
    if (rejection.isPresent()) {
      consumedToday = consumptionRepository.consumedOn(accountId, request.channel(), businessDate);
    } else {
      Optional<BigDecimal> consumed =
          consumptionRepository.tryConsume(
              accountId, request.channel(), businessDate, request.amount(), limit.dailyLimit());
      if (consumed.isPresent()) {
        consumedToday = consumed.get();
      } else {
        rejection = Optional.of(RejectionReason.DAILY_LIMIT_EXCEEDED);
        consumedToday =
            consumptionRepository.consumedOn(accountId, request.channel(), businessDate);
      }
    }

    TransferValidation validation =
        new TransferValidation(
            request.transferId(),
            accountId,
            request.channel(),
            request.amount(),
            request.currency(),
            rejection.isPresent() ? ValidationStatus.REJECTED : ValidationStatus.APPROVED,
            rejection.orElse(null),
            limit.dailyLimit(),
            limit.perOperationLimit(),
            consumedToday,
            new ChannelUsage(limit, consumedToday).availableToday(),
            businessDate,
            now,
            false);
    if (!validationRepository.saveIfAbsent(validation)) {
      throw new ConcurrentTransferIdException();
    }
    return validation;
  }

  private static TransferValidation replay(TransferValidation existing, TransferRequest request) {
    if (!existing.samePayloadAs(request)) {
      throw new TransferIdConflictException(request.transferId(), request.accountId());
    }
    return existing.asReplay();
  }

  /** Signals a lost same-transferId race; rolls back the attempt (and its consumption). */
  private static final class ConcurrentTransferIdException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ConcurrentTransferIdException() {
      super("transferId inserted concurrently", null, false, false);
    }
  }
}
