package com.banamex.transferlimits.application;

import com.banamex.transferlimits.domain.Channel;
import com.banamex.transferlimits.domain.ChannelLimit;
import com.banamex.transferlimits.domain.TransferRequest;
import com.banamex.transferlimits.domain.port.BusinessDateProvider;
import com.banamex.transferlimits.infrastructure.time.SystemBusinessDateProvider;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Shared test data and lightweight test doubles of the application layer. */
final class TestFixtures {

  static final String ACCOUNT = "ACC-001";
  static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Mexico_City");

  /** 2026-11-19 12:00 in Mexico City. */
  static final Instant NOW = Instant.parse("2026-11-19T18:00:00Z");

  static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 11, 19);

  private TestFixtures() {}

  static BusinessDateProvider fixedDateProvider() {
    return new SystemBusinessDateProvider(Clock.fixed(NOW, ZoneOffset.UTC), BUSINESS_ZONE);
  }

  static ChannelLimit limit(Channel channel, String daily, String perOperation) {
    return new ChannelLimit(
        ACCOUNT, channel, new BigDecimal(daily), new BigDecimal(perOperation), "MXN", NOW);
  }

  static TransferRequest request(UUID transferId, Channel channel, String amount) {
    return new TransferRequest(transferId, ACCOUNT, channel, new BigDecimal(amount), "MXN");
  }

  /** Runs the callback directly, counting the transactions that were started. */
  static final class CountingTransactionOperations implements TransactionOperations {

    private final AtomicInteger transactions = new AtomicInteger();

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
      transactions.incrementAndGet();
      return action.doInTransaction(new SimpleTransactionStatus());
    }

    int transactions() {
      return transactions.get();
    }
  }

  /** Records every stopped observation context. */
  static final class RecordingObservationHandler
      implements ObservationHandler<Observation.Context> {

    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStop(Observation.Context context) {
      stopped.add(context);
    }

    List<Observation.Context> stopped() {
      return stopped;
    }

    Observation.Context last() {
      return stopped.getLast();
    }
  }
}
