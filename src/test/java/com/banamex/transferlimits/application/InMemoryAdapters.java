package com.banamex.transferlimits.application;

import com.banamex.transferlimits.domain.Amounts;
import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.TransferValidation;
import com.banamex.transferlimits.domain.port.ConsumptionRepository;
import com.banamex.transferlimits.domain.port.LimitRepository;
import com.banamex.transferlimits.domain.port.TransferValidationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Thread-safe in-memory adapters that reproduce the database semantics the use case relies on.
 *
 * <ul>
 *   <li>{@link Consumption#tryConsume} mirrors {@code UPDATE ... SET consumed = consumed + :amount
 *       WHERE ... AND consumed + :amount <= :dailyLimit RETURNING consumed}: the check and the
 *       increment run atomically inside {@link ConcurrentHashMap#compute}.
 *   <li>{@link Validations#saveIfAbsent} mirrors {@code INSERT ... ON CONFLICT DO NOTHING} with
 *       {@link ConcurrentHashMap#putIfAbsent}.
 *   <li>{@link Transactions} undoes the consumption of an attempt that throws, like a rollback, and
 *       releases the consumption row lock taken by a successful update at the end of the attempt.
 * </ul>
 */
final class InMemoryAdapters {

  private InMemoryAdapters() {}

  /**
   * Minimal transaction manager: per thread it keeps an undo log (run on rollback) and the row
   * locks to release when the transaction ends (commit or rollback).
   */
  static final class Transactions implements TransactionOperations {

    private final ThreadLocal<Deque<Runnable>> undoLog = new ThreadLocal<>();
    private final ThreadLocal<Deque<Runnable>> completionLog = new ThreadLocal<>();

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
      Deque<Runnable> undo = new ArrayDeque<>();
      Deque<Runnable> completion = new ArrayDeque<>();
      undoLog.set(undo);
      completionLog.set(completion);
      try {
        return action.doInTransaction(new SimpleTransactionStatus());
      } catch (RuntimeException e) {
        undo.forEach(Runnable::run);
        throw e;
      } finally {
        completion.forEach(Runnable::run);
        undoLog.remove();
        completionLog.remove();
      }
    }

    void onRollback(Runnable compensation) {
      Deque<Runnable> undo = undoLog.get();
      if (undo != null) {
        undo.push(compensation);
      }
    }

    void onCompletion(Runnable action) {
      Deque<Runnable> completion = completionLog.get();
      if (completion == null) {
        action.run();
      } else {
        completion.push(action);
      }
    }
  }

  /** Consumption store keyed by account, channel and business date. */
  static final class Consumption implements ConsumptionRepository {

    private final Map<String, BigDecimal> consumed = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> rowLocks = new ConcurrentHashMap<>();
    private final Transactions transactions;

    Consumption(Transactions transactions) {
      this.transactions = transactions;
    }

    private static String key(String accountId, Channel channel, LocalDate businessDate) {
      return accountId + '|' + channel + '|' + businessDate;
    }

    @Override
    public BigDecimal consumedOn(String accountId, Channel channel, LocalDate businessDate) {
      return consumed.getOrDefault(key(accountId, channel, businessDate), Amounts.ZERO);
    }

    @Override
    public Optional<BigDecimal> tryConsume(
        String accountId,
        Channel channel,
        LocalDate businessDate,
        BigDecimal amount,
        BigDecimal dailyLimit) {
      String key = key(accountId, channel, businessDate);
      // Like PostgreSQL, the UPDATE waits for the row lock of a concurrent writer; a successful
      // update keeps the row locked until the end of the transaction.
      ReentrantLock rowLock = rowLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
      rowLock.lock();
      AtomicReference<BigDecimal> updated = new AtomicReference<>();
      consumed.compute(
          key,
          (ignored, current) -> {
            BigDecimal base = current == null ? Amounts.ZERO : current;
            BigDecimal next = base.add(amount);
            if (next.compareTo(dailyLimit) <= 0) {
              updated.set(next);
              return next;
            }
            return base;
          });
      if (updated.get() == null) {
        rowLock.unlock();
        return Optional.empty();
      }
      transactions.onRollback(
          () -> consumed.compute(key, (ignored, current) -> current.subtract(amount)));
      transactions.onCompletion(rowLock::unlock);
      return Optional.of(updated.get());
    }
  }

  /** Limit store. */
  static final class Limits implements LimitRepository {

    private final Map<String, ChannelLimit> limits = new ConcurrentHashMap<>();

    private static String key(String accountId, Channel channel) {
      return accountId + '|' + channel;
    }

    @Override
    public List<ChannelLimit> findByAccount(String accountId) {
      return limits.values().stream()
          .filter(limit -> limit.accountId().equals(accountId))
          .sorted(Comparator.comparing(ChannelLimit::channel))
          .toList();
    }

    @Override
    public Optional<ChannelLimit> find(String accountId, Channel channel) {
      return Optional.ofNullable(limits.get(key(accountId, channel)));
    }

    @Override
    public ChannelLimit save(ChannelLimit limit) {
      limits.put(key(limit.accountId(), limit.channel()), limit);
      return limit;
    }
  }

  /** Idempotency store. */
  static final class Validations implements TransferValidationRepository {

    private final Map<UUID, TransferValidation> validations = new ConcurrentHashMap<>();

    @Override
    public Optional<TransferValidation> find(UUID transferId) {
      return Optional.ofNullable(validations.get(transferId));
    }

    @Override
    public boolean saveIfAbsent(TransferValidation validation) {
      return validations.putIfAbsent(validation.transferId(), validation) == null;
    }

    int size() {
      return validations.size();
    }
  }
}
