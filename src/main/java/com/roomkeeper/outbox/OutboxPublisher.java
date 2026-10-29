package com.roomkeeper.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.roomkeeper.common.JobLocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every few seconds (roomkeeper.outbox.publish-interval), hands waiting outbox events to
 * their consumers, oldest first. Every instance schedules it; the one holding
 * {@code lock:outbox} runs it.
 * <p>
 * Each event goes out in its own transaction: locked, handed to its consumers, marked
 * published. Delivery is at least once (a run that dies after a consumer acted but before
 * its commit hands the event over again), so consumers tolerate duplicates. If a consumer
 * throws, that transaction rolls back and another records the failed attempt; the event is
 * tried once per run until it's parked.
 */
@Component
public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	/** Shorter than the publish interval, so a crashed instance only costs a run. */
	private static final Duration LEASE = Duration.ofSeconds(4);

	/** Events read per query; a longer queue is worked through a batch at a time. */
	static final int BATCH = 100;

	private final OutboxEventRepository events;

	private final List<OutboxConsumer> consumers;

	private final JobLocks locks;

	private final TransactionTemplate transaction;

	private final Clock clock;

	OutboxPublisher(OutboxEventRepository events, List<OutboxConsumer> consumers, JobLocks locks,
			PlatformTransactionManager transactions, Clock clock) {
		this.events = events;
		this.consumers = consumers;
		this.locks = locks;
		this.transaction = new TransactionTemplate(transactions);
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${roomkeeper.outbox.publish-interval}")
	public void run() {
		locks.runLocked("outbox", LEASE, this::publishWaiting)
			.filter(published -> published > 0)
			.ifPresent(published -> log.info("Published {} outbox events", published));
	}

	/** Tries every waiting event once; returns how many were published. */
	int publishWaiting() {
		int published = 0;
		long afterId = 0;
		List<Long> ids;
		do {
			ids = events.findWaitingIdsAfter(afterId, Limit.of(BATCH));
			for (long id : ids) {
				if (publish(id)) {
					published++;
				}
			}
			// Past the failures too, so each event is tried at most once a run
			afterId = ids.isEmpty() ? afterId : ids.getLast();
		}
		while (ids.size() == BATCH);
		return published;
	}

	private boolean publish(long id) {
		try {
			return Boolean.TRUE.equals(transaction.execute(status -> deliver(id)));
		}
		catch (RuntimeException ex) {
			transaction.executeWithoutResult(status -> recordFailure(id, ex));
			return false;
		}
	}

	/** Hands the event to its consumers and marks it published; false if it's no longer this run's to send. */
	private boolean deliver(long id) {
		OutboxEvent event = events.lockWaiting(id).orElse(null);
		if (event == null) {
			return false;
		}
		List<OutboxConsumer> takers = consumers.stream()
			.filter(consumer -> consumer.types().contains(event.getType()))
			.toList();
		if (takers.isEmpty()) {
			throw new IllegalStateException("No consumer takes " + event.getType() + " events");
		}
		takers.forEach(consumer -> consumer.consume(event));
		event.markPublished(clock.instant());
		return true;
	}

	private void recordFailure(long id, RuntimeException ex) {
		OutboxEvent event = events.lockWaiting(id).orElse(null);
		if (event == null) {
			return;
		}
		event.recordFailure(ex.getClass().getSimpleName() + ": " + ex.getMessage(), clock.instant());
		if (event.isParked()) {
			log.error("Outbox event {} ({}) failed {} times; parked, no more retries", id, event.getType(),
					event.getAttempts(), ex);
		}
		else {
			log.warn("Outbox event {} ({}) failed, attempt {} of {}; retrying next run", id, event.getType(),
					event.getAttempts(), OutboxEvent.MAX_ATTEMPTS, ex);
		}
	}

}
