package com.roomkeeper.common.metrics;

import io.micrometer.core.instrument.Counter;
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

	public BusinessMetrics(MeterRegistry registry) {
		this.registry = registry;
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
	}

	/** A guest held rooms: a new booking, not a retried request answered with an earlier one. */
	public void bookingCreated() {
		afterCommit(bookingsCreated);
	}

	/** A hold ran out unpaid and its rooms went back on sale. */
	public void holdExpired() {
		afterCommit(holdsExpired);
	}

	/** A webhook delivery found its event already applied, and changed nothing. */
	public void duplicateWebhookIgnored() {
		afterCommit(duplicateWebhooksIgnored);
	}

	/** A rate limit turned a request away; {@code route} names the limit, such as login. */
	public void rateLimited(String route) {
		Counter.builder("roomkeeper.rate.limit.rejections")
			.description("Requests a rate limit turned away, by route")
			.tag("route", route)
			.register(registry)
			.increment();
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
