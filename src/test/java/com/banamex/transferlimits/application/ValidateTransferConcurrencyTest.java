package com.banamex.transferlimits.application;

import static com.banamex.transferlimits.application.TestFixtures.ACCOUNT;
import static com.banamex.transferlimits.application.TestFixtures.BUSINESS_DATE;
import static org.assertj.core.api.Assertions.assertThat;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.RejectionReason;
import com.banamex.transferlimits.domain.TransferRequest;
import com.banamex.transferlimits.domain.TransferValidation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Concurrency guarantees of {@link ValidateTransferUseCase} (PROMPT section 6) using in-memory
 * adapters that reproduce the atomic conditional UPDATE and the INSERT ... ON CONFLICT DO NOTHING.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ValidateTransferConcurrencyTest {

  private static final String DAILY_LIMIT = "5000.00";

  private InMemoryAdapters.Transactions transactions;
  private InMemoryAdapters.Consumption consumption;
  private InMemoryAdapters.Validations validations;
  private ValidateTransferUseCase useCase;

  @BeforeEach
  void setUp() {
    transactions = new InMemoryAdapters.Transactions();
    consumption = new InMemoryAdapters.Consumption(transactions);
    validations = new InMemoryAdapters.Validations();
    InMemoryAdapters.Limits limits = new InMemoryAdapters.Limits();
    limits.save(TestFixtures.limit(Channel.MOBILE_APP, DAILY_LIMIT, "1000.00"));
    useCase =
        new ValidateTransferUseCase(
            limits,
            consumption,
            validations,
            TestFixtures.fixedDateProvider(),
            transactions,
            ObservationRegistry.NOOP);
  }

  /** Runs one validation per thread, all released at the same instant by a start gate. */
  private List<TransferValidation> runConcurrently(
      int threads, IntFunction<TransferRequest> requests) throws Exception {
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<TransferValidation>> futures = new ArrayList<>();
    try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
      for (int index = 0; index < threads; index++) {
        TransferRequest request = requests.apply(index);
        futures.add(
            executor.submit(() -> {
              ready.countDown();
              start.await();
              return useCase.execute(request);
            }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<TransferValidation> results = new ArrayList<>();
      for (Future<TransferValidation> future : futures) {
        results.add(future.get(20, TimeUnit.SECONDS));
      }
      return results;
    }
  }

  @ParameterizedTest(name = "{0} threads x {1} MXN against a 5000.00 daily limit")
  @CsvSource({"50, 300.00", "64, 1000.00", "100, 0.01", "80, 333.33", "50, 100.00"})
  void approvedAmountNeverExceedsTheDailyLimit(int threads, String amount) throws Exception {
    BigDecimal transferAmount = new BigDecimal(amount);

    List<TransferValidation> results =
        runConcurrently(
            threads, index -> TestFixtures.request(UUID.randomUUID(), Channel.MOBILE_APP, amount));

    BigDecimal dailyLimit = new BigDecimal(DAILY_LIMIT);
    int expectedApprovals =
        Math.min(threads, dailyLimit.divideToIntegralValue(transferAmount).intValueExact());
    List<TransferValidation> approved =
        results.stream().filter(TransferValidation::approved).toList();
    List<TransferValidation> rejected =
        results.stream().filter(result -> !result.approved()).toList();
    BigDecimal approvedSum =
        approved.stream().map(TransferValidation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal consumed = consumption.consumedOn(ACCOUNT, Channel.MOBILE_APP, BUSINESS_DATE);

    assertThat(approvedSum).isLessThanOrEqualTo(dailyLimit);
    assertThat(approved).hasSize(expectedApprovals);
    assertThat(consumed).isEqualByComparingTo(approvedSum);
    assertThat(rejected)
        .hasSize(threads - expectedApprovals)
        .allSatisfy(
            result -> assertThat(result.reason()).isEqualTo(RejectionReason.DAILY_LIMIT_EXCEEDED));
    assertThat(results).noneMatch(TransferValidation::replayed);
    assertThat(validations.size()).isEqualTo(threads);
    assertThat(approved)
        .allSatisfy(result -> assertThat(result.consumedToday()).isLessThanOrEqualTo(dailyLimit));
    assertThat(approved).extracting(TransferValidation::consumedToday).doesNotHaveDuplicates();
  }

  @RepeatedTest(10)
  void sixtyThreadsWithDistinctIdsApproveExactlyFloorOfLimitOverAmount() throws Exception {
    List<TransferValidation> results =
        runConcurrently(
            60, index -> TestFixtures.request(UUID.randomUUID(), Channel.MOBILE_APP, "300.00"));

    long approvedCount = results.stream().filter(TransferValidation::approved).count();
    assertThat(approvedCount).isEqualTo(16);
    assertThat(consumption.consumedOn(ACCOUNT, Channel.MOBILE_APP, BUSINESS_DATE))
        .isEqualByComparingTo("4800.00");
  }

  @RepeatedTest(10)
  void sameTransferIdFromManyThreadsIsApprovedAndConsumedOnce() throws Exception {
    UUID transferId = UUID.randomUUID();

    List<TransferValidation> results =
        runConcurrently(
            50, index -> TestFixtures.request(transferId, Channel.MOBILE_APP, "750.00"));

    assertThat(results).allMatch(TransferValidation::approved);
    assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
    assertThat(results).filteredOn(TransferValidation::replayed).hasSize(49);
    assertThat(results)
        .extracting(TransferValidation::consumedToday)
        .allSatisfy(value -> assertThat(value).isEqualByComparingTo("750.00"));
    assertThat(consumption.consumedOn(ACCOUNT, Channel.MOBILE_APP, BUSINESS_DATE))
        .isEqualByComparingTo("750.00");
    assertThat(validations.size()).isEqualTo(1);
  }

  @RepeatedTest(5)
  void sameTransferIdThatFillsTheWholeLimitIsStillApprovedOnce() throws Exception {
    UUID transferId = UUID.randomUUID();
    useCase.execute(TestFixtures.request(UUID.randomUUID(), Channel.MOBILE_APP, "1000.00"));
    useCase.execute(TestFixtures.request(UUID.randomUUID(), Channel.MOBILE_APP, "1000.00"));
    useCase.execute(TestFixtures.request(UUID.randomUUID(), Channel.MOBILE_APP, "1000.00"));
    useCase.execute(TestFixtures.request(UUID.randomUUID(), Channel.MOBILE_APP, "1000.00"));

    List<TransferValidation> results =
        runConcurrently(
            50, index -> TestFixtures.request(transferId, Channel.MOBILE_APP, "1000.00"));

    assertThat(results).allMatch(TransferValidation::approved);
    assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
    assertThat(consumption.consumedOn(ACCOUNT, Channel.MOBILE_APP, BUSINESS_DATE))
        .isEqualByComparingTo(DAILY_LIMIT);
  }

  @RepeatedTest(5)
  void mixedRetriesAndNewTransfersNeverExceedTheLimit() throws Exception {
    UUID[] ids = new UUID[20];
    for (int index = 0; index < ids.length; index++) {
      ids[index] = UUID.randomUUID();
    }

    List<TransferValidation> results =
        runConcurrently(
            100,
            index -> TestFixtures.request(ids[index % ids.length], Channel.MOBILE_APP, "400.00"));

    List<TransferValidation> originals =
        results.stream().filter(result -> !result.replayed()).toList();
    long approvedOriginals = originals.stream().filter(TransferValidation::approved).count();
    assertThat(originals).hasSize(ids.length);
    assertThat(approvedOriginals).isEqualTo(12);
    assertThat(consumption.consumedOn(ACCOUNT, Channel.MOBILE_APP, BUSINESS_DATE))
        .isEqualByComparingTo("4800.00");
    assertThat(validations.size()).isEqualTo(ids.length);
    for (UUID id : ids) {
      assertThat(results)
          .filteredOn(result -> result.transferId().equals(id))
          .extracting(TransferValidation::status)
          .containsOnly(
              results.stream()
                  .filter(result -> result.transferId().equals(id) && !result.replayed())
                  .findFirst()
                  .orElseThrow()
                  .status());
    }
  }
}
