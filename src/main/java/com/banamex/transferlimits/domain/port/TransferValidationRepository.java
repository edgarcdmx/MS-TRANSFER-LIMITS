package com.banamex.transferlimits.domain.port;

import com.banamex.transferlimits.domain.TransferValidation;
import java.util.Optional;
import java.util.UUID;

/** Output port for persisted transfer validations (idempotency store). */
public interface TransferValidationRepository {

  /**
   * Finds a validation by its idempotency key.
   *
   * @param transferId transfer id
   * @return the stored validation (with {@code replayed = false}), or empty
   */
  Optional<TransferValidation> find(UUID transferId);

  /**
   * Inserts the validation unless one with the same {@code transferId} already exists.
   *
   * <p>Must not throw on a duplicate key nor leave the transaction unusable: a concurrent insert of
   * the same key is reported by returning {@code false} once the other transaction has committed.
   *
   * @param validation validation to insert
   * @return {@code true} if inserted, {@code false} if the transfer id already existed
   */
  boolean saveIfAbsent(TransferValidation validation);
}
