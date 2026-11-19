package com.banamex.transferlimits.infrastructure.persistence;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.port.LimitRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** JPA adapter of {@link LimitRepository}. */
@Component
public class LimitRepositoryAdapter implements LimitRepository {

  private final AccountChannelLimitJpaRepository repository;

  /**
   * Creates the adapter.
   *
   * @param repository Spring Data repository
   */
  public LimitRepositoryAdapter(AccountChannelLimitJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<ChannelLimit> findByAccount(String accountId) {
    return repository.findByIdAccountId(accountId).stream()
        .map(AccountChannelLimitEntity::toDomain)
        .sorted(Comparator.comparing(ChannelLimit::channel))
        .toList();
  }

  @Override
  public Optional<ChannelLimit> find(String accountId, Channel channel) {
    return repository
        .findById(new AccountChannelLimitId(accountId, channel))
        .map(AccountChannelLimitEntity::toDomain);
  }

  /**
   * Upserts the limit: updates the managed entity if it exists (the {@code @Version} column guards
   * against lost updates) or persists a new one.
   */
  @Override
  public ChannelLimit save(ChannelLimit limit) {
    AccountChannelLimitEntity entity =
        repository
            .findById(new AccountChannelLimitId(limit.accountId(), limit.channel()))
            .map(existing -> {
              existing.apply(limit);
              return existing;
            })
            .orElseGet(() -> new AccountChannelLimitEntity(limit));
    return repository.saveAndFlush(entity).toDomain();
  }
}
