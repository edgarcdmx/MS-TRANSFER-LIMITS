package com.banamex.transferlimits.infrastructure.persistence;

import com.banamex.transferlimits.domain.Amounts;
import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.port.ConsumptionRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL adapter of {@link ConsumptionRepository}.
 *
 * <p>{@link #tryConsume} relies on a conditional {@code UPDATE ... RETURNING}: PostgreSQL locks the
 * row and, in READ COMMITTED, re-evaluates the {@code WHERE} against the committed value of a
 * concurrent writer, so the consumption can never exceed the daily limit (ADR-002). All values are
 * bound as parameters. Must be called inside a transaction.
 */
@Component
public class ConsumptionRepositoryAdapter implements ConsumptionRepository {

  /** Ensures the row of the business date exists (idempotent, never blocks on an existing row). */
  static final String ENSURE_ROW_SQL =
      """
      INSERT INTO daily_consumption (account_id, channel, business_date, consumed)
      VALUES (:accountId, :channel, :businessDate, 0)
      ON CONFLICT (account_id, channel, business_date) DO NOTHING
      """;

  /** Atomic conditional increment; returns no row when the limit would be exceeded. */
  static final String CONSUME_SQL =
      """
      UPDATE daily_consumption
         SET consumed = consumed + :amount
       WHERE account_id = :accountId
         AND channel = :channel
         AND business_date = :businessDate
         AND consumed + :amount <= :dailyLimit
      RETURNING consumed
      """;

  private final EntityManager entityManager;
  private final DailyConsumptionJpaRepository repository;

  /**
   * Creates the adapter.
   *
   * @param entityManager shared, transaction-bound entity manager
   * @param repository Spring Data repository for reads
   */
  public ConsumptionRepositoryAdapter(
      EntityManager entityManager, DailyConsumptionJpaRepository repository) {
    this.entityManager = entityManager;
    this.repository = repository;
  }

  @Override
  public BigDecimal consumedOn(String accountId, Channel channel, LocalDate businessDate) {
    return repository
        .findConsumed(accountId, channel, businessDate)
        .map(Amounts::normalize)
        .orElse(Amounts.ZERO);
  }

  @Override
  public Optional<BigDecimal> tryConsume(
      String accountId,
      Channel channel,
      LocalDate businessDate,
      BigDecimal amount,
      BigDecimal dailyLimit) {
    entityManager
        .createNativeQuery(ENSURE_ROW_SQL)
        .setParameter("accountId", accountId)
        .setParameter("channel", channel.name())
        .setParameter("businessDate", businessDate)
        .executeUpdate();
    List<?> rows =
        entityManager
            .createNativeQuery(CONSUME_SQL, BigDecimal.class)
            .setParameter("amount", amount)
            .setParameter("accountId", accountId)
            .setParameter("channel", channel.name())
            .setParameter("businessDate", businessDate)
            .setParameter("dailyLimit", dailyLimit)
            .getResultList();
    return rows.stream().findFirst().map(value -> Amounts.normalize((BigDecimal) value));
  }
}
