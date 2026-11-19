package com.banamex.transferlimits.infrastructure.persistence;

import com.banamex.transferlimits.domain.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * Composite primary key of {@code daily_consumption}.
 *
 * @param accountId account identifier
 * @param channel channel
 * @param businessDate business date
 */
@Embeddable
public record DailyConsumptionId(
    @Column(name = "account_id", nullable = false, length = 34) String accountId,
    @Enumerated(EnumType.STRING) @Column(name = "channel", nullable = false, length = 16)
        Channel channel,
    @Column(name = "business_date", nullable = false) LocalDate businessDate)
    implements Serializable {}
