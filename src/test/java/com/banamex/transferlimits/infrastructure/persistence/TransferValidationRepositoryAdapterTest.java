package com.banamex.transferlimits.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.RejectionReason;
import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.ValidationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferValidationRepositoryAdapterTest {

  private static final UUID TRANSFER_ID = UUID.fromString("7d1b0f7e-3c2a-4a55-9a51-1c2b3d4e5f60");
  private static final LocalDate DATE = LocalDate.of(2026, 11, 19);
  private static final Instant VALIDATED_AT = Instant.parse("2026-11-19T18:00:00.123456Z");

  private EntityManager entityManager;
  private TransferValidationJpaRepository repository;
  private Query insert;
  private TransferValidationRepositoryAdapter adapter;

  @BeforeEach
  void setUp() {
    entityManager = mock(EntityManager.class);
    repository = mock(TransferValidationJpaRepository.class);
    insert = mock(Query.class, RETURNS_SELF);
    when(entityManager.createNativeQuery(TransferValidationRepositoryAdapter.INSERT_SQL))
        .thenReturn(insert);
    adapter = new TransferValidationRepositoryAdapter(entityManager, repository);
  }

  static TransferValidation validation(ValidationStatus status, RejectionReason reason) {
    return new TransferValidation(
        TRANSFER_ID,
        "ACC-1",
        Channel.BRANCH,
        new BigDecimal("1200.00"),
        "MXN",
        status,
        reason,
        new BigDecimal("5000.00"),
        new BigDecimal("1000.00"),
        new BigDecimal("300.00"),
        new BigDecimal("4700.00"),
        DATE,
        VALIDATED_AT,
        false);
  }

  @Test
  void findMapsTheStoredEntity() {
    TransferValidation stored =
        validation(ValidationStatus.REJECTED, RejectionReason.PER_OPERATION_LIMIT_EXCEEDED);
    when(repository.findById(TRANSFER_ID))
        .thenReturn(Optional.of(new TransferValidationEntity(stored)));

    assertThat(adapter.find(TRANSFER_ID)).contains(stored);
  }

  @Test
  void findIsEmptyForUnknownIds() {
    when(repository.findById(TRANSFER_ID)).thenReturn(Optional.empty());

    assertThat(adapter.find(TRANSFER_ID)).isEmpty();
  }

  @Test
  void saveIfAbsentReturnsTrueWhenOneRowIsInserted() {
    when(insert.executeUpdate()).thenReturn(1);

    boolean inserted =
        adapter.saveIfAbsent(
            validation(ValidationStatus.REJECTED, RejectionReason.DAILY_LIMIT_EXCEEDED));

    assertThat(inserted).isTrue();
    verify(insert).setParameter("transferId", TRANSFER_ID);
    verify(insert).setParameter("accountId", "ACC-1");
    verify(insert).setParameter("channel", "BRANCH");
    verify(insert).setParameter("amount", new BigDecimal("1200.00"));
    verify(insert).setParameter("currency", "MXN");
    verify(insert).setParameter("status", "REJECTED");
    verify(insert).setParameter("reasonCode", "DAILY_LIMIT_EXCEEDED");
    verify(insert).setParameter("dailyLimit", new BigDecimal("5000.00"));
    verify(insert).setParameter("perOperationLimit", new BigDecimal("1000.00"));
    verify(insert).setParameter("consumedToday", new BigDecimal("300.00"));
    verify(insert).setParameter("businessDate", DATE);
    verify(insert).setParameter("createdAt", VALIDATED_AT);
  }

  @Test
  void saveIfAbsentReturnsFalseWhenTheIdAlreadyExists() {
    when(insert.executeUpdate()).thenReturn(0);

    boolean inserted = adapter.saveIfAbsent(validation(ValidationStatus.APPROVED, null));

    assertThat(inserted).isFalse();
    verify(insert).setParameter("status", "APPROVED");
    verify(insert).setParameter("reasonCode", (Object) null);
  }

  @Test
  void insertIgnoresDuplicates() {
    assertThat(TransferValidationRepositoryAdapter.INSERT_SQL)
        .contains("ON CONFLICT (transfer_id) DO NOTHING");
  }
}
