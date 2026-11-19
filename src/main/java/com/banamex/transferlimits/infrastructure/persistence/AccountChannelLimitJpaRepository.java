package com.banamex.transferlimits.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository of {@link AccountChannelLimitEntity}. */
public interface AccountChannelLimitJpaRepository
    extends JpaRepository<AccountChannelLimitEntity, AccountChannelLimitId> {

  /**
   * Finds every channel limit of an account.
   *
   * @param accountId account identifier
   * @return the entities, in no particular order
   */
  List<AccountChannelLimitEntity> findByIdAccountId(String accountId);
}
