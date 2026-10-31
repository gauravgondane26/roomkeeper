package com.roomkeeper.common.metrics;

import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Counts what happens to the business, beside the HTTP, JVM and cache metrics Spring
 * records itself (the search cache's hit ratio comes from those: {@code cache.gets} by
 * result). A count made inside a transaction waits for it to commit, so a change that rolls
 * back isn't counted.
 */
@Component
public class BusinessMetrics {

	private final MeterRegistry registry;

	private final Counter bookingsCreated;

	private final Counter holdsExpired;

	private final Counter duplicateWebhooksIgnored;

	private final Counter repricingDropped;

	private final Counter optimisticRetries;

	private final Counter outboxSent;

	private final Counter outboxPublished;

	private final Counter outboxClaimed;

	private final Counter outboxResent;

	public BusinessMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.optimisticRetries = Counter.builder("roomkeeper.bookings.optimistic.retries")
			.description("Optimistic holds that lost a race for their nights and started over")
			.register(registry);
		// Not "created": Prometheus reserves the _created suffix and would drop it from the name
		this.bookingsCreated = Counter.builder("roomkeeper.bookings.initiated")
			.description("Bookings created: rooms held for a guest")
			.register(registry);
		this.holdsExpired = Counter.builder("roomkeeper.holds.expired")
			.description("Holds that ran out unpaid, their rooms put back on sale")
			.register(registry);
		this.duplicateWebhooksIgnored = Counter.builder("roomkeeper.webhooks.duplicates.ignored")
			.description("Payment webhook deliveries ignored because their event was already applied")
			.register(registry);
		this.repricingDropped = Counter.builder("roomkeeper.repricing.dropped")
			.description("Cheapest-price recomputes dropped because the repricing queue was full")
			.register(registry);
		this.outboxSent = Counter.builder("roomkeeper.outbox.sent")
			.description("Outbox events added to their consumer's Redis stream, resends included")
			.register(registry);
		this.outboxPublished = Counter.builder("roomkeeper.outbox.published")
			.description("Outbox events their consumer acted on")
			.register(registry);
		this.outboxClaimed = Counter.builder("roomkeeper.outbox.claimed")
			.description("Stream entries taken over after sitting unacknowledged (a failed delivery, or a dead reader)")
			.register(registry);
		this.outboxResent = Counter.builder("roomkeeper.outbox.resent")
			.description("Outbox events sent again because their stream didn't deliver them in time")
			.register(registry);
	}

	/** An outbox event was added to its stream. Counted straight away: its sending committed first. */
	public void outboxSent() {
		outboxSent.increment();
	}

	/** An outbox event's consumer acted on it, and that committed. */
	public void outboxPublished() {
		outboxPublished.increment();
	}

	/** Readers took over this many stream entries left unacknowledged. */
	public void outboxClaimed(int entries) {
		outboxClaimed.increment(entries);
	}

	/** This many outbox events were marked to be sent again. */
	public void outboxResent(int events) {
		outboxResent.increment(events);
	}

	/**
	 * Gauges one consumer's stream: entries read and not yet acknowledged, and entries kept.
	 * Each is read from Redis when scraped, and reads as no value (NaN) while Redis fails.
	 */
	public void outboxStream(String consumer, Supplier<Number> pending, Supplier<Number> length) {
		Gauge.builder("roomkeeper.outbox.stream.pending", () -> orNaN(pending))
			.description("Entries of the consumer's outbox stream read and not yet acknowledged")
			.tag("consumer", consumer)
			.register(registry);
		Gauge.builder("roomkeeper.outbox.stream.length", () -> orNaN(length))
			.description("Entries kept in the consumer's outbox stream")
			.tag("consumer", consumer)
			.register(registry);
	}

	private static Number orNaN(Supplier<Number> value) {
		try {
			return value.get();
		}
		catch (RuntimeException ex) {
			return Double.NaN;
		}
	}

	/** A guest held rooms: a new booking, not a retried request answered with an earlier one. */
	public void bookingCreated() {
		afterCommit(bookingsCreated);
	}

	/**
	 * An optimistic hold found its nights changed by another and started over. Counted
	 * straight away: the attempt that lost was rolled back.
	 */
	public void optimisticRetry() {
		optimisticRetries.increment();
	}

	/** A hold ran out unpaid and its rooms went back on sale. */
	public void holdExpired() {
		afterCommit(holdsExpired);
	}

	/** A webhook delivery found its event already applied, and changed nothing. */
	public void duplicateWebhookIgnored() {
		afterCommit(duplicateWebhooksIgnored);
	}

	/**
	 * A hotel's cheapest prices weren't recomputed after a sale because the repricing queue was
	 * full; they're stale until its nights change again. Counted straight away: it happens
	 * after the sale committed.
	 */
	public void repricingDropped() {
		repricingDropped.increment();
	}

	/**
	 * The count of requests a rate limit turns away, for the route it guards (such as login).
	 * Taken when the limit is set up, so it starts at 0 instead of appearing with its first
	 * rejection, which Prometheus's rate() and increase() would miss.
	 */
	public Counter rateLimitRejections(String route) {
		return Counter.builder("roomkeeper.rate.limit.rejections")
			.description("Requests a rate limit turned away, by route")
			.tag("route", route)
			.register(registry);
	}

	private static void afterCommit(Counter counter) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			counter.increment();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				counter.increment();
			}
		});
	}

}
