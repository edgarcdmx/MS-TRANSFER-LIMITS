package com.banamex.transferlimits.infrastructure.persistence;

import com.banamex.transferlimits.domain.Channel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository of {@link DailyConsumptionEntity} (reads only). */
public interface DailyConsumptionJpaRepository
    extends JpaRepository<DailyConsumptionEntity, DailyConsumptionId> {

  /**
   * Reads the consumed amount straight from the database (scalar query, bypasses the persistence
   * context so it always sees the latest committed or own-transaction value).
   *
   * @param accountId account identifier
   * @param channel channel
   * @param businessDate business date
   * @return the consumed amount, or empty if there is no row for that date
   */
  @Query(
      "select c.consumed from DailyConsumptionEntity c"
          + " where c.id.accountId = :accountId and c.id.channel = :channel"
          + " and c.id.businessDate = :businessDate")
  Optional<BigDecimal> findConsumed(
      @Param("accountId") String accountId,
      @Param("channel") Channel channel,
      @Param("businessDate") LocalDate businessDate);
}
