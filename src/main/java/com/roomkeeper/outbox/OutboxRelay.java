package com.roomkeeper.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.roomkeeper.common.JobLocks;
import com.roomkeeper.common.metrics.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.Limit;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends unsent outbox events to their consumer's stream, oldest first: each is locked and
 * marked sent in its own transaction, then, once that has committed, added to the stream
 * ({@code XADD}, capped near roomkeeper.outbox.max-stream-length). An entry carries only
 * the event's id and type; the reader loads the event from Postgres. An event marked sent
 * that never reached its stream (the relay died in between) is resent by the sweeper.
 * <p>
 * A commit that wrote an event wakes the relay at once. As a safety net it also runs every
 * roomkeeper.outbox.relay-interval, on the instance holding {@code lock:outbox}. Relays that
 * overlap (a wake and the safety net, or two instances' wakes) split the queue by row
 * lock, so each event is sent once. If Redis fails, the run stops and the rest wait for the
 * next: an unsent event is delayed, never lost.
 */
@Component
public class OutboxRelay implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	/** Shorter than the relay interval, so a crashed instance only costs a run. */
	private static final Duration LEASE = Duration.ofSeconds(20);

	/** Events read per query; a longer queue is worked through a batch at a time. */
	static final int BATCH = 100;

	private final OutboxEventRepository events;

	private final OutboxRoutes routes;

	private final StringRedisTemplate redis;

	private final JobLocks locks;

	private final TransactionTemplate transaction;

	private final OutboxProperties properties;

	private final BusinessMetrics metrics;

	private final Clock clock;

	private final AtomicBoolean wakePending = new AtomicBoolean();

	private final ExecutorService wakes = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("outbox-relay").factory());

	OutboxRelay(OutboxEventRepository events, OutboxRoutes routes, StringRedisTemplate redis, JobLocks locks,
			PlatformTransactionManager transactions, OutboxProperties properties, BusinessMetrics metrics, Clock clock) {
		this.events = events;
		this.routes = routes;
		this.redis = redis;
		this.locks = locks;
		this.transaction = new TransactionTemplate(transactions);
		this.properties = properties;
		this.metrics = metrics;
		this.clock = clock;
	}

	/** The safety net: sends whatever a wake missed (a crash, a Redis outage, a resend). */
	@Scheduled(fixedDelayString = "${roomkeeper.outbox.relay-interval}")
	public void run() {
		locks.runLocked("outbox", LEASE, this::relayWaiting)
			.filter(sent -> sent > 0)
			.ifPresent(sent -> log.info("Sent {} outbox events to their streams", sent));
	}

	/**
	 * Sends soon, on the relay's own thread, unless live delivery is off. Wakes that come
	 * while one is waiting to run are merged into it, so a burst of commits costs one relay.
	 */
	public void wake() {
		if (properties.liveDelivery() && wakePending.compareAndSet(false, true)) {
			wakes.execute(() -> {
				wakePending.set(false);
				try {
					relayWaiting();
				}
				catch (RuntimeException ex) {
					log.warn("Outbox relay failed; the safety net sends what's left", ex);
				}
			});
		}
	}

	/** Sends every unsent event, or as many as it can before Redis or Postgres fails; returns how many. */
	public int relayWaiting() {
		int sent = 0;
		long afterId = 0;
		List<Long> ids;
		do {
			ids = events.findUnsentIdsAfter(afterId, Limit.of(BATCH));
			for (long id : ids) {
				Send send;
				try {
					send = transaction.execute(status -> markSent(id));
				}
				catch (RuntimeException ex) {
					log.warn("Couldn't mark outbox event {} sent; it and the rest wait for the next run", id, ex);
					return sent;
				}
				if (send == null) {
					continue;
				}
				try {
					add(send);
					metrics.outboxSent();
					sent++;
				}
				catch (RuntimeException ex) {
					unsend(id);
					log.warn("Couldn't send outbox event {} to {}; it and the rest wait for the next run", id,
							send.stream(), ex);
					return sent;
				}
			}
			afterId = ids.isEmpty() ? afterId : ids.getLast();
		}
		while (ids.size() == BATCH);
		return sent;
	}

	/** An event marked sent, to be added to its stream. */
	private record Send(long id, OutboxEventType type, String stream) {
	}

	/**
	 * Marks the event sent, committed before it's added to its stream: a reader that gets
	 * the entry at once then finds the event settled, not still locked by this relay. Null
	 * if it's no longer this relay's to send: sent, published or parked meanwhile, or locked
	 * by another relay.
	 */
	private Send markSent(long id) {
		OutboxEvent event = events.lockUnsent(id).orElse(null);
		if (event == null) {
			return null;
		}
		event.markSent(clock.instant());
		return new Send(id, event.getType(), OutboxRoutes.streamOf(routes.consumerOf(event.getType())));
	}

	private void add(Send send) {
		redis.opsForStream()
			.add(StreamRecords.string(Map.of("eventId", String.valueOf(send.id()), "type", send.type().name()))
				.withStreamKey(send.stream()), XAddOptions.maxlen(properties.maxStreamLength()).approximateTrimming(true));
		log.debug("Sent outbox event {} ({}) to {}", send.id(), send.type(), send.stream());
	}

	/**
	 * Marks unsent again an event its stream never got, so the next run sends it. If even
	 * that fails, the sweeper resends it once resend-after has passed.
	 */
	private void unsend(long id) {
		try {
			transaction.executeWithoutResult(status -> events.unsend(id));
		}
		catch (RuntimeException ex) {
			log.warn("Couldn't mark outbox event {} unsent; the sweeper resends it", id, ex);
		}
	}

	@Override
	public void destroy() {
		wakes.shutdownNow();
	}

}
