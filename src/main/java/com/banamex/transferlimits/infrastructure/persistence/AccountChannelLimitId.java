package com.banamex.transferlimits.infrastructure.persistence;

import com.banamex.transferlimits.domain.Channel;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;

/**
 * Composite primary key of {@code account_channel_limit}.
 *
 * @param accountId account identifier
 * @param channel channel
 */
@Embeddable
public record AccountChannelLimitId(
    @Column(name = "account_id", nullable = false, length = 34) String accountId,
    @Enumerated(EnumType.STRING) @Column(name = "channel", nullable = false, length = 16)
        Channel channel)
    implements Serializable {}
