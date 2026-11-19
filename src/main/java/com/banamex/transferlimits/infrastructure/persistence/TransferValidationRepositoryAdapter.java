package com.banamex.transferlimits.infrastructure.persistence;

import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.port.TransferValidationRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL adapter of {@link TransferValidationRepository}.
 *
 * <p>Inserts use {@code ON CONFLICT (transfer_id) DO NOTHING}: a duplicate key does not raise an
 * error (which would abort the PostgreSQL transaction); if a concurrent transaction holds the same
 * key uncommitted, PostgreSQL waits for it and reports 0 inserted rows once it commits.
 */
@Component
public class TransferValidationRepositoryAdapter implements TransferValidationRepository {

  /** Idempotent insert; all values are bound as parameters. */
  static final String INSERT_SQL =
      """
      INSERT INTO transfer_validation (transfer_id, account_id, channel, amount, currency, status,
                                       reason_code, daily_limit, per_operation_limit,
                                       consumed_today, business_date, created_at)
      VALUES (:transferId, :accountId, :channel, :amount, :currency, :status,
              CAST(:reasonCode AS VARCHAR), :dailyLimit, :perOperationLimit,
              :consumedToday, :businessDate, :createdAt)
      ON CONFLICT (transfer_id) DO NOTHING
      """;

  private final EntityManager entityManager;
  private final TransferValidationJpaRepository repository;

  /**
   * Creates the adapter.
   *
   * @param entityManager shared, transaction-bound entity manager
   * @param repository Spring Data repository for reads
   */
  public TransferValidationRepositoryAdapter(
      EntityManager entityManager, TransferValidationJpaRepository repository) {
    this.entityManager = entityManager;
    this.repository = repository;
  }

  @Override
  public Optional<TransferValidation> find(UUID transferId) {
    return repository.findById(transferId).map(TransferValidationEntity::toDomain);
  }

  @Override
  public boolean saveIfAbsent(TransferValidation validation) {
    int inserted =
        entityManager
            .createNativeQuery(INSERT_SQL)
            .setParameter("transferId", validation.transferId())
            .setParameter("accountId", validation.accountId())
            .setParameter("channel", validation.channel().name())
            .setParameter("amount", validation.amount())
            .setParameter("currency", validation.currency())
            .setParameter("status", validation.status().name())
            .setParameter(
                "reasonCode", validation.reason() == null ? null : validation.reason().name())
            .setParameter("dailyLimit", validation.dailyLimit())
            .setParameter("perOperationLimit", validation.perOperationLimit())
            .setParameter("consumedToday", validation.consumedToday())
            .setParameter("businessDate", validation.businessDate())
            .setParameter("createdAt", validation.validatedAt())
            .executeUpdate();
    return inserted == 1;
  }
}
