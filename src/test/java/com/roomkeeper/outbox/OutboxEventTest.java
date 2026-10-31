package com.roomkeeper.outbox;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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

	@Test
	void aWaitingEventIsSentToItsStreamUntilItsPublished() {
		OutboxEvent event = new OutboxEvent(42L, OutboxEventType.BOOKING_CONFIRMED, "{}", NOW);
		assertThat(event.isPublished()).isFalse();

		event.markSent(NOW);
		event.markSent(NOW.plusSeconds(120));
		event.markPublished(NOW.plusSeconds(121));

		assertThat(event.isPublished()).isTrue();
		assertThatIllegalStateException().isThrownBy(() -> event.markSent(NOW.plusSeconds(200)));
	}

	@Test
	void aParkedEventIsntSentAgain() {
		OutboxEvent event = new OutboxEvent(42L, OutboxEventType.BOOKING_CONFIRMED, "{}", NOW);
		for (int failure = 1; failure <= OutboxEvent.MAX_ATTEMPTS; failure++) {
			event.recordFailure("IllegalStateException: down", NOW);
		}

		assertThat(event.isPublished()).isFalse();
		assertThatIllegalStateException().isThrownBy(() -> event.markSent(NOW));
	}

}
