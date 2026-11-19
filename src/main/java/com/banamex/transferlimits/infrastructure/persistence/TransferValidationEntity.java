package com.banamex.transferlimits.infrastructure.persistence;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.ChannelUsage;
import com.banamex.transferlimits.domain.RejectionReason;
import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.ValidationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Read-only JPA mapping of {@code transfer_validation}. Inserts are done with a native {@code
 * INSERT ... ON CONFLICT DO NOTHING} in {@link TransferValidationRepositoryAdapter}.
 */
@Entity
@Immutable
@Table(name = "transfer_validation")
public class TransferValidationEntity {

  @Id
  @Column(name = "transfer_id", nullable = false)
  private UUID transferId;

  @Column(name = "account_id", nullable = false, length = 34)
  private String accountId;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false, length = 16)
  private Channel channel;

  @Column(name = "amount", nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private ValidationStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason_code", length = 64)
  private RejectionReason reasonCode;

  @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
  private BigDecimal dailyLimit;

  @Column(name = "per_operation_limit", nullable = false, precision = 19, scale = 2)
  private BigDecimal perOperationLimit;

  @Column(name = "consumed_today", nullable = false, precision = 19, scale = 2)
  private BigDecimal consumedToday;

  @Column(name = "business_date", nullable = false)
  private LocalDate businessDate;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** Constructor required by JPA. */
  protected TransferValidationEntity() {}

  /**
   * Creates an entity from a domain validation.
   *
   * @param validation domain validation
   */
  public TransferValidationEntity(TransferValidation validation) {
    this.transferId = validation.transferId();
    this.accountId = validation.accountId();
    this.channel = validation.channel();
    this.amount = validation.amount();
    this.currency = validation.currency();
    this.status = validation.status();
    this.reasonCode = validation.reason();
    this.dailyLimit = validation.dailyLimit();
    this.perOperationLimit = validation.perOperationLimit();
    this.consumedToday = validation.consumedToday();
    this.businessDate = validation.businessDate();
    this.createdAt = validation.validatedAt();
  }

  /**
   * Converts to the domain model; {@code availableToday} is derived from the snapshot.
   *
   * @return the domain validation with {@code replayed = false}
   */
  public TransferValidation toDomain() {
    ChannelLimit limit =
        new ChannelLimit(accountId, channel, dailyLimit, perOperationLimit, currency, createdAt);
    return new TransferValidation(
        transferId,
        accountId,
        channel,
        amount,
        currency,
        status,
        reasonCode,
        limit.dailyLimit(),
        limit.perOperationLimit(),
        consumedToday,
        new ChannelUsage(limit, consumedToday).availableToday(),
        businessDate,
        createdAt,
        false);
  }

  /**
   * Primary key.
   *
   * @return the transfer id
   */
  public UUID getTransferId() {
    return transferId;
  }
}
