package com.banamex.transferlimits.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository of {@link TransferValidationEntity} (reads only). */
public interface TransferValidationJpaRepository
    extends JpaRepository<TransferValidationEntity, UUID> {}
