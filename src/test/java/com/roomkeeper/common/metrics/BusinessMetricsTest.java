package com.roomkeeper.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessMetricsTest {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final BusinessMetrics metrics = new BusinessMetrics(registry);

	@AfterEach
	void endTransaction() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void everyCounterIsThereFromTheStart() {
		assertThat(count("roomkeeper.bookings.initiated")).isZero();
		assertThat(count("roomkeeper.holds.expired")).isZero();
		assertThat(count("roomkeeper.webhooks.duplicates.ignored")).isZero();
		assertThat(count("roomkeeper.repricing.dropped")).isZero();
		assertThat(count("roomkeeper.outbox.sent")).isZero();
		assertThat(count("roomkeeper.outbox.published")).isZero();
		assertThat(count("roomkeeper.outbox.claimed")).isZero();
		assertThat(count("roomkeeper.outbox.resent")).isZero();
	}

	@Test
	void countsWhatTheOutboxSendsPublishesClaimsAndResends() {
		metrics.outboxSent();
		metrics.outboxSent();
		metrics.outboxPublished();
		metrics.outboxClaimed(3);
		metrics.outboxResent(5);

		assertThat(count("roomkeeper.outbox.sent")).isEqualTo(2);
		assertThat(count("roomkeeper.outbox.published")).isOne();
		assertThat(count("roomkeeper.outbox.claimed")).isEqualTo(3);
		assertThat(count("roomkeeper.outbox.resent")).isEqualTo(5);
	}

	@Test
	void gaugesEachOutboxStreamAndReadsNothingWhileRedisIsDown() {
		metrics.outboxStream("refunds", () -> 2, () -> 40);
		metrics.outboxStream("booking-emails", () -> {
			throw new IllegalStateException("Redis command timed out");
		}, () -> 7);

		assertThat(gauge("roomkeeper.outbox.stream.pending", "refunds")).isEqualTo(2);
		assertThat(gauge("roomkeeper.outbox.stream.length", "refunds")).isEqualTo(40);
		assertThat(gauge("roomkeeper.outbox.stream.pending", "booking-emails")).isNaN();
		assertThat(gauge("roomkeeper.outbox.stream.length", "booking-emails")).isEqualTo(7);
	}

	private double gauge(String name, String consumer) {
		return registry.get(name).tag("consumer", consumer).gauge().value();
	}

	@Test
	void countsAtOnceOutsideATransaction() {
		metrics.bookingCreated();
		metrics.rateLimitRejections("login").increment();

		assertThat(count("roomkeeper.bookings.initiated")).isOne();
		assertThat(registry.get("roomkeeper.rate.limit.rejections").tag("route", "login").counter().count()).isOne();
	}

	@Test
	void countsInsideATransactionOnceItCommits() {
		TransactionSynchronizationManager.initSynchronization();

		metrics.holdExpired();
		metrics.duplicateWebhookIgnored();
		assertThat(count("roomkeeper.holds.expired")).isZero();

		TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
		assertThat(count("roomkeeper.holds.expired")).isOne();
		assertThat(count("roomkeeper.webhooks.duplicates.ignored")).isOne();
	}

	@Test
	void doesNotCountWhatRolledBack() {
		TransactionSynchronizationManager.initSynchronization();

		metrics.bookingCreated();
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		assertThat(count("roomkeeper.bookings.initiated")).isZero();
	}

	private double count(String name) {
		return registry.get(name).counter().count();
	}

}
