package com.banamex.transferlimits.domain.port;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import java.util.List;
import java.util.Optional;

/** Output port for the channel limits of an account. */
public interface LimitRepository {

  /**
   * Finds every channel limit of an account.
   *
   * @param accountId account identifier
   * @return the limits ordered by {@link Channel} declaration order; empty if none
   */
  List<ChannelLimit> findByAccount(String accountId);

  /**
   * Finds the limit of one channel.
   *
   * @param accountId account identifier
   * @param channel channel
   * @return the limit, or empty if not configured
   */
  Optional<ChannelLimit> find(String accountId, Channel channel);

  /**
   * Creates or updates (upsert) the limit of a channel.
   *
   * @param limit limit to store
   * @return the stored limit
   */
  ChannelLimit save(ChannelLimit limit);
}
