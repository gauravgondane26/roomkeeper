package com.roomkeeper.outbox;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A failing event is retried, and parked once it has failed too often. */
class OutboxEventTest {

	private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

	@Test
	void eachFailureIsCountedAndTheTenthParksTheEvent() {
		OutboxEvent event = new OutboxEvent(42L, OutboxEventType.BOOKING_CONFIRMED, "{}", NOW);

		for (int failure = 1; failure < OutboxEvent.MAX_ATTEMPTS; failure++) {
			event.recordFailure("IllegalStateException: down", NOW);
			assertThat(event.getAttempts()).isEqualTo(failure);
			assertThat(event.isParked()).as("after %d failures", failure).isFalse();
		}
		event.recordFailure("IllegalStateException: down", NOW);

		assertThat(event.getAttempts()).isEqualTo(10);
		assertThat(event.isParked()).isTrue();
	}

}
