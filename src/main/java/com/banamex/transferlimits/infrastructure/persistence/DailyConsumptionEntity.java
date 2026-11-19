package com.banamex.transferlimits.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

/**
 * Read-only JPA mapping of {@code daily_consumption}. Writes are done exclusively with the atomic
 * native statements of {@link ConsumptionRepositoryAdapter}.
 */
@Entity
@Immutable
@Table(name = "daily_consumption")
public class DailyConsumptionEntity {

  @EmbeddedId private DailyConsumptionId id;

  @Column(name = "consumed", nullable = false, precision = 19, scale = 2)
  private BigDecimal consumed;

  /** Constructor required by JPA. */
  protected DailyConsumptionEntity() {}

  /**
   * Creates an entity (used by tests and mappers).
   *
   * @param id primary key
   * @param consumed consumed amount
   */
  public DailyConsumptionEntity(DailyConsumptionId id, BigDecimal consumed) {
    this.id = id;
    this.consumed = consumed;
  }

  /**
   * Primary key.
   *
   * @return the key
   */
  public DailyConsumptionId getId() {
    return id;
  }

  /**
   * Consumed amount.
   *
   * @return the amount
   */
  public BigDecimal getConsumed() {
    return consumed;
  }
}
