package com.banamex.transferlimits.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.RejectionReason;
import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.ValidationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PersistenceEntitiesTest {

  private static final Instant NOW = Instant.parse("2026-11-19T18:00:00Z");
  private static final LocalDate DATE = LocalDate.of(2026, 11, 19);

  @Test
  void accountChannelLimitEntityRoundTrip() {
    ChannelLimit limit =
        new ChannelLimit(
            "ACC-1", Channel.WEB, new BigDecimal("5000"), new BigDecimal("1000"), "MXN", NOW);

    AccountChannelLimitEntity entity = new AccountChannelLimitEntity(limit);

    assertThat(entity.getId()).isEqualTo(new AccountChannelLimitId("ACC-1", Channel.WEB));
    assertThat(entity.getVersion()).isNull();
    assertThat(entity.toDomain()).isEqualTo(limit);
  }

  @Test
  void accountChannelLimitEntityApplyReplacesValuesButNotTheId() {
    AccountChannelLimitEntity entity =
        new AccountChannelLimitEntity(
            new ChannelLimit("ACC-1", Channel.WEB, BigDecimal.TEN, BigDecimal.ONE, "MXN", NOW));
    ChannelLimit other =
        new ChannelLimit(
            "ACC-1",
            Channel.WEB,
            new BigDecimal("20"),
            new BigDecimal("2"),
            "USD",
            NOW.plusSeconds(1));

    entity.apply(other);

    assertThat(entity.toDomain()).isEqualTo(other);
    assertThat(entity.getId().accountId()).isEqualTo("ACC-1");
  }

  @Test
  void accountChannelLimitEntityDefaultConstructorForJpa() {
    AccountChannelLimitEntity entity = new AccountChannelLimitEntity();

    assertThat(entity.getId()).isNull();
    assertThat(entity.getVersion()).isNull();
  }

  @Test
  void dailyConsumptionEntity() {
    DailyConsumptionId id = new DailyConsumptionId("ACC-1", Channel.BRANCH, DATE);

    DailyConsumptionEntity entity = new DailyConsumptionEntity(id, new BigDecimal("42.00"));

    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getConsumed()).isEqualByComparingTo("42");
    assertThat(new DailyConsumptionEntity().getId()).isNull();
  }

  @Test
  void transferValidationEntityRoundTripOfAnApproval() {
    TransferValidation approval =
        TransferValidationRepositoryAdapterTest.validation(ValidationStatus.APPROVED, null);

    TransferValidationEntity entity = new TransferValidationEntity(approval);

    assertThat(entity.getTransferId()).isEqualTo(approval.transferId());
    assertThat(entity.toDomain()).isEqualTo(approval);
  }

  @Test
  void transferValidationEntityRoundTripOfRejection() {
    TransferValidation rejection =
        TransferValidationRepositoryAdapterTest.validation(
            ValidationStatus.REJECTED, RejectionReason.PER_OPERATION_LIMIT_EXCEEDED);

    TransferValidation result = new TransferValidationEntity(rejection).toDomain();

    assertThat(result).isEqualTo(rejection);
    assertThat(result.replayed()).isFalse();
    assertThat(result.reason()).isEqualTo(RejectionReason.PER_OPERATION_LIMIT_EXCEEDED);
  }

  @Test
  void transferValidationEntityRecomputesAvailableAndNormalizesLimits() {
    TransferValidationEntity entity =
        new TransferValidationEntity(
            TransferValidationRepositoryAdapterTest.validation(ValidationStatus.APPROVED, null));
    ReflectionTestUtils.setField(entity, "dailyLimit", new BigDecimal("1000"));
    ReflectionTestUtils.setField(entity, "consumedToday", new BigDecimal("1000.00"));
    ReflectionTestUtils.setField(entity, "perOperationLimit", new BigDecimal("1000"));

    TransferValidation result = entity.toDomain();

    assertThat(result.dailyLimit()).hasToString("1000.00");
    assertThat(result.perOperationLimit()).hasToString("1000.00");
    assertThat(result.availableToday()).hasToString("0.00");
  }

  @Test
  void transferValidationEntityDefaultConstructorForJpa() {
    assertThat(new TransferValidationEntity().getTransferId()).isNull();
  }

  @Test
  void compositeIdsHaveValueSemantics() {
    assertThat(new AccountChannelLimitId("ACC-1", Channel.WEB))
        .isEqualTo(new AccountChannelLimitId("ACC-1", Channel.WEB))
        .hasSameHashCodeAs(new AccountChannelLimitId("ACC-1", Channel.WEB))
        .isNotEqualTo(new AccountChannelLimitId("ACC-1", Channel.BRANCH))
        .isNotEqualTo(new AccountChannelLimitId("ACC-2", Channel.WEB));
    assertThat(new DailyConsumptionId("ACC-1", Channel.WEB, DATE))
        .isEqualTo(new DailyConsumptionId("ACC-1", Channel.WEB, DATE))
        .hasSameHashCodeAs(new DailyConsumptionId("ACC-1", Channel.WEB, DATE))
        .isNotEqualTo(new DailyConsumptionId("ACC-1", Channel.WEB, DATE.plusDays(1)));
  }
}
