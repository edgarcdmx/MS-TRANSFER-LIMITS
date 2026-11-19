package com.banamex.transferlimits.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.banamex.transferlimits.domain.Channel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ConsumptionRepositoryAdapterTest {

  private static final LocalDate DATE = LocalDate.of(2026, 11, 19);

  private EntityManager entityManager;
  private DailyConsumptionJpaRepository repository;
  private Query ensureRow;
  private Query consume;
  private ConsumptionRepositoryAdapter adapter;

  @BeforeEach
  void setUp() {
    entityManager = mock(EntityManager.class);
    repository = mock(DailyConsumptionJpaRepository.class);
    ensureRow = mock(Query.class, RETURNS_SELF);
    consume = mock(Query.class, RETURNS_SELF);
    when(entityManager.createNativeQuery(ConsumptionRepositoryAdapter.ENSURE_ROW_SQL))
        .thenReturn(ensureRow);
    when(entityManager.createNativeQuery(
            ConsumptionRepositoryAdapter.CONSUME_SQL, BigDecimal.class))
        .thenReturn(consume);
    adapter = new ConsumptionRepositoryAdapter(entityManager, repository);
  }

  @Test
  void consumedOnNormalizesTheStoredValue() {
    when(repository.findConsumed("ACC-1", Channel.WEB, DATE))
        .thenReturn(Optional.of(new BigDecimal("12.5")));

    assertThat(adapter.consumedOn("ACC-1", Channel.WEB, DATE)).hasToString("12.50");
  }

  @Test
  void consumedOnIsZeroWhenThereIsNoRow() {
    when(repository.findConsumed("ACC-1", Channel.WEB, DATE)).thenReturn(Optional.empty());

    assertThat(adapter.consumedOn("ACC-1", Channel.WEB, DATE)).hasToString("0.00");
  }

  @Test
  void tryConsumeEnsuresTheRowThenRunsTheConditionalUpdate() {
    when(ensureRow.executeUpdate()).thenReturn(1);
    when(consume.getResultList()).thenReturn(List.of(new BigDecimal("1500")));

    Optional<BigDecimal> result =
        adapter.tryConsume(
            "ACC-1", Channel.MOBILE_APP, DATE, new BigDecimal("500.00"), new BigDecimal("5000.00"));

    assertThat(result).hasValueSatisfying(value -> assertThat(value).hasToString("1500.00"));
    InOrder order = inOrder(entityManager, ensureRow, consume);
    order.verify(entityManager).createNativeQuery(ConsumptionRepositoryAdapter.ENSURE_ROW_SQL);
    order.verify(ensureRow).executeUpdate();
    order
        .verify(entityManager)
        .createNativeQuery(ConsumptionRepositoryAdapter.CONSUME_SQL, BigDecimal.class);
    order.verify(consume).getResultList();
    verify(ensureRow).setParameter("accountId", "ACC-1");
    verify(ensureRow).setParameter("channel", "MOBILE_APP");
    verify(ensureRow).setParameter("businessDate", DATE);
    verify(consume).setParameter("amount", new BigDecimal("500.00"));
    verify(consume).setParameter("accountId", "ACC-1");
    verify(consume).setParameter("channel", "MOBILE_APP");
    verify(consume).setParameter("businessDate", DATE);
    verify(consume).setParameter("dailyLimit", new BigDecimal("5000.00"));
  }

  @Test
  void tryConsumeIsEmptyWhenTheUpdateMatchesNoRow() {
    when(consume.getResultList()).thenReturn(List.of());

    Optional<BigDecimal> result =
        adapter.tryConsume(
            "ACC-1", Channel.WEB, DATE, new BigDecimal("600.00"), new BigDecimal("5000.00"));

    assertThat(result).isEmpty();
  }

  @Test
  void sqlKeepsTheLimitConditionInsideTheAtomicUpdate() {
    assertThat(ConsumptionRepositoryAdapter.CONSUME_SQL)
        .contains("SET consumed = consumed + :amount")
        .contains("AND consumed + :amount <= :dailyLimit")
        .contains("RETURNING consumed");
    assertThat(ConsumptionRepositoryAdapter.ENSURE_ROW_SQL).contains("ON CONFLICT");
  }
}
