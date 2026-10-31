package com.roomkeeper.outbox;

import java.time.Clock;
import java.time.Duration;

import com.roomkeeper.common.JobLocks;
import com.roomkeeper.common.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every minute, on the instance holding {@code lock:outbox-sweep}, marks unsent the events
 * sent more than roomkeeper.outbox.resend-after ago and still not published, then wakes the
 * relay to send them again. That's how an entry Redis lost (a flush, a restart without
 * persistence, a stream trimmed past it) still gets delivered: Postgres, not the stream,
 * says what's left to do. An event its consumer keeps failing on isn't resent: each failure
 * moves its sent time on.
 */
@Component
public class OutboxSweeper {

	private static final Logger log = LoggerFactory.getLogger(OutboxSweeper.class);

	/** Shorter than the minute between runs. */
	private static final Duration LEASE = Duration.ofSeconds(50);

	private final OutboxEventRepository events;

	private final OutboxRelay relay;

	private final JobLocks locks;

	private final TransactionTemplate transaction;

	private final OutboxProperties properties;

	private final BusinessMetrics metrics;

	private final Clock clock;

	OutboxSweeper(OutboxEventRepository events, OutboxRelay relay, JobLocks locks,
			PlatformTransactionManager transactions, OutboxProperties properties, BusinessMetrics metrics, Clock clock) {
		this.events = events;
		this.relay = relay;
		this.locks = locks;
		this.transaction = new TransactionTemplate(transactions);
		this.properties = properties;
		this.metrics = metrics;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "60s")
	public void run() {
		locks.runLocked("outbox-sweep", LEASE, this::resendStale)
			.filter(resent -> resent > 0)
			.ifPresent(resent -> log.warn("Sending {} outbox events again: sent, but not delivered within {}", resent,
					properties.resendAfter()));
	}

	/** Marks the stale sends unsent and wakes the relay; returns how many. */
	public int resendStale() {
		int resent = transaction.execute(
				status -> events.resendSentBefore(clock.instant().minus(properties.resendAfter())));
		if (resent > 0) {
			metrics.outboxResent(resent);
			relay.wake();
		}
		return resent;
	}

}
