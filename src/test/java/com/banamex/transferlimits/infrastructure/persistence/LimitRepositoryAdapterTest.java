package com.banamex.transferlimits.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class LimitRepositoryAdapterTest {

  private static final Instant UPDATED_AT = Instant.parse("2026-11-19T18:00:00Z");

  private AccountChannelLimitJpaRepository repository;
  private LimitRepositoryAdapter adapter;

  @BeforeEach
  void setUp() {
    repository = mock(AccountChannelLimitJpaRepository.class);
    when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    adapter = new LimitRepositoryAdapter(repository);
  }

  private static ChannelLimit limit(Channel channel, String daily, String perOperation) {
    return new ChannelLimit(
        "ACC-1", channel, new BigDecimal(daily), new BigDecimal(perOperation), "MXN", UPDATED_AT);
  }

  @Test
  void findByAccountMapsAndSortsByChannel() {
    when(repository.findByIdAccountId("ACC-1"))
        .thenReturn(
            List.of(
                new AccountChannelLimitEntity(limit(Channel.BRANCH, "3", "1")),
                new AccountChannelLimitEntity(limit(Channel.MOBILE_APP, "1", "1")),
                new AccountChannelLimitEntity(limit(Channel.WEB, "2", "1"))));

    List<ChannelLimit> result = adapter.findByAccount("ACC-1");

    assertThat(result)
        .extracting(ChannelLimit::channel)
        .containsExactly(Channel.MOBILE_APP, Channel.WEB, Channel.BRANCH);
    assertThat(result.getFirst()).isEqualTo(limit(Channel.MOBILE_APP, "1", "1"));
  }

  @Test
  void findByAccountIsEmptyWhenNothingIsConfigured() {
    when(repository.findByIdAccountId("ACC-1")).thenReturn(List.of());

    assertThat(adapter.findByAccount("ACC-1")).isEmpty();
  }

  @Test
  void findLooksUpByCompositeId() {
    ChannelLimit limit = limit(Channel.WEB, "5000", "1000");
    when(repository.findById(new AccountChannelLimitId("ACC-1", Channel.WEB)))
        .thenReturn(Optional.of(new AccountChannelLimitEntity(limit)));

    assertThat(adapter.find("ACC-1", Channel.WEB)).contains(limit);
  }

  @Test
  void findIsEmptyWhenTheChannelIsNotConfigured() {
    when(repository.findById(any())).thenReturn(Optional.empty());

    assertThat(adapter.find("ACC-1", Channel.BRANCH)).isEmpty();
  }

  @Test
  void saveInsertsNewEntityWhenAbsent() {
    when(repository.findById(any())).thenReturn(Optional.empty());
    ChannelLimit limit = limit(Channel.WEB, "5000", "1000");

    ChannelLimit result = adapter.save(limit);

    ArgumentCaptor<AccountChannelLimitEntity> saved =
        ArgumentCaptor.forClass(AccountChannelLimitEntity.class);
    verify(repository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getId()).isEqualTo(new AccountChannelLimitId("ACC-1", Channel.WEB));
    assertThat(saved.getValue().getVersion()).isNull();
    assertThat(result).isEqualTo(limit);
  }

  @Test
  void saveUpdatesTheManagedEntityKeepingItsVersion() {
    AccountChannelLimitEntity existing =
        new AccountChannelLimitEntity(limit(Channel.WEB, "100", "10"));
    ReflectionTestUtils.setField(existing, "version", 3L);
    when(repository.findById(new AccountChannelLimitId("ACC-1", Channel.WEB)))
        .thenReturn(Optional.of(existing));
    ChannelLimit updated =
        new ChannelLimit(
            "ACC-1",
            Channel.WEB,
            new BigDecimal("9000"),
            new BigDecimal("900"),
            "MXN",
            UPDATED_AT.plusSeconds(60));

    ChannelLimit result = adapter.save(updated);

    verify(repository).saveAndFlush(existing);
    assertThat(existing.getVersion()).isEqualTo(3L);
    assertThat(existing.toDomain()).isEqualTo(updated);
    assertThat(result).isEqualTo(updated);
  }
}
