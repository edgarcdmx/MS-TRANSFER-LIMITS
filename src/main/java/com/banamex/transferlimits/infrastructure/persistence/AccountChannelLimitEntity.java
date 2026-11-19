package com.banamex.transferlimits.infrastructure.persistence;

import com.banamex.transferlimits.domain.ChannelLimit;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA mapping of {@code account_channel_limit}; optimistic locking via {@code version}. */
@Entity
@Table(name = "account_channel_limit")
public class AccountChannelLimitEntity {

  @EmbeddedId private AccountChannelLimitId id;

  @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
  private BigDecimal dailyLimit;

  @Column(name = "per_operation_limit", nullable = false, precision = 19, scale = 2)
  private BigDecimal perOperationLimit;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Constructor required by JPA. */
  protected AccountChannelLimitEntity() {}

  /**
   * Creates a new (transient) entity from a domain limit.
   *
   * @param limit domain limit
   */
  public AccountChannelLimitEntity(ChannelLimit limit) {
    this.id = new AccountChannelLimitId(limit.accountId(), limit.channel());
    apply(limit);
  }

  /**
   * Copies the mutable values of a domain limit into this entity.
   *
   * @param limit domain limit with the same key
   */
  public final void apply(ChannelLimit limit) {
    this.dailyLimit = limit.dailyLimit();
    this.perOperationLimit = limit.perOperationLimit();
    this.currency = limit.currency();
    this.updatedAt = limit.updatedAt();
  }

  /**
   * Converts to the domain model.
   *
   * @return the domain limit
   */
  public ChannelLimit toDomain() {
    return new ChannelLimit(
        id.accountId(), id.channel(), dailyLimit, perOperationLimit, currency, updatedAt);
  }

  /**
   * Primary key.
   *
   * @return the key
   */
  public AccountChannelLimitId getId() {
    return id;
  }

  /**
   * Optimistic lock version.
   *
   * @return the version, null while transient
   */
  public Long getVersion() {
    return version;
  }
}
